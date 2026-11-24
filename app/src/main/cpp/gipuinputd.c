// gipuinputd: tiny uinput gamepad daemon, run as the shell user through Shizuku's
// process API (Shizuku.newProcess). It replaces the Shizuku "UserService" of the original
// GIP Bridge, which cannot start on MediaTek Android 16 ROMs (NullPointerException in
// LoadedApk.makeApplicationInner inside Shizuku's ServiceStarter, Shizuku issue #1198).
//
// Packaged as lib/arm64-v8a/libgipuinputd.so so Android extracts it with exec permission.
//
// Protocol: text lines on stdin, one command per line.
//   O <slot> <name...>              open a uinput gamepad   -> "R O <slot> <1|0>"
//   C <slot>                        close it
//   K <slot> <code> <value>         key event (Linux key code)
//   A <slot> x y z rz gas brake hx hy   all axes + SYN
//   P                               ping -> "R P"
//   Q                               quit
// Output lines on stdout:
//   R ...                           replies
//   F <slot> <1=play|2=stop> <strong> <weak> <duration_ms>   rumble
//   L <message>                     log
// EOF on stdin (the app died) destroys all devices and exits.
//
// Codes (include/uapi/linux/input-event-codes.h): BTN_A=0x130 BTN_B=0x131 BTN_X=0x133 BTN_Y=0x134
// BTN_TL=0x136 BTN_TR=0x137 BTN_START=0x13b BTN_MODE=0x13c BTN_THUMBL=0x13d BTN_THUMBR=0x13e.
// The device id 045e:02fd matches /system/usr/keylayout/Vendor_045e_Product_02fd.kl (checked on
// the Nothing Phone 2a): View = KEY_BACK(158), Xbox = KEY_HOMEPAGE(172) or BTN_MODE,
// triggers = ABS_GAS/ABS_BRAKE, right stick = ABS_Z/ABS_RZ.

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define MAX_SLOTS 4
#define MAX_FF_EFFECTS 16

typedef struct {
    int in_use;
    unsigned short strong;
    unsigned short weak;
    unsigned short duration_ms;
} ff_effect_t;

typedef struct {
    int fd;
    volatile int running;
    pthread_t ff_thread;
    int has_thread;
    ff_effect_t effects[MAX_FF_EFFECTS];
} slot_t;

static slot_t g_slots[MAX_SLOTS];
static pthread_mutex_t g_out_lock = PTHREAD_MUTEX_INITIALIZER;

static void out(const char *fmt, ...) {
    va_list ap;
    pthread_mutex_lock(&g_out_lock);
    va_start(ap, fmt);
    vfprintf(stdout, fmt, ap);
    va_end(ap);
    fputc('\n', stdout);
    fflush(stdout);
    pthread_mutex_unlock(&g_out_lock);
}

static void write_event(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        out("L write_event(type=%u code=%u) failed: %s", type, code, strerror(errno));
    }
}

static void handle_ff_event(int slot, int fd, struct input_event *ev) {
    slot_t *s = &g_slots[slot];
    if (ev->type == EV_UINPUT && ev->code == UI_FF_UPLOAD) {
        struct uinput_ff_upload upload;
        memset(&upload, 0, sizeof(upload));
        upload.request_id = (unsigned int) ev->value;
        if (ioctl(fd, UI_BEGIN_FF_UPLOAD, &upload) < 0) return;
        int id = upload.effect.id;
        if (upload.effect.type == FF_RUMBLE && id >= 0 && id < MAX_FF_EFFECTS) {
            s->effects[id].in_use = 1;
            s->effects[id].strong = upload.effect.u.rumble.strong_magnitude;
            s->effects[id].weak = upload.effect.u.rumble.weak_magnitude;
            s->effects[id].duration_ms = upload.effect.replay.length;
        }
        upload.retval = 0;
        ioctl(fd, UI_END_FF_UPLOAD, &upload);
    } else if (ev->type == EV_UINPUT && ev->code == UI_FF_ERASE) {
        struct uinput_ff_erase erase;
        memset(&erase, 0, sizeof(erase));
        erase.request_id = (unsigned int) ev->value;
        if (ioctl(fd, UI_BEGIN_FF_ERASE, &erase) < 0) return;
        int id = (int) erase.effect_id;
        if (id >= 0 && id < MAX_FF_EFFECTS) s->effects[id].in_use = 0;
        erase.retval = 0;
        ioctl(fd, UI_END_FF_ERASE, &erase);
    } else if (ev->type == EV_FF) {
        int id = ev->code;
        if (id < 0 || id >= MAX_FF_EFFECTS || !s->effects[id].in_use) return;
        out("F %d %d %u %u %u", slot, ev->value != 0 ? 1 : 2,
            s->effects[id].strong, s->effects[id].weak, s->effects[id].duration_ms);
    }
}

static void *ff_thread_main(void *arg) {
    int slot = (int) (long) arg;
    slot_t *s = &g_slots[slot];
    struct input_event ev;
    while (s->running) {
        struct pollfd pfd = { .fd = s->fd, .events = POLLIN };
        int r = poll(&pfd, 1, 250);
        if (r <= 0) continue;
        ssize_t n = read(s->fd, &ev, sizeof(ev));
        if (n != (ssize_t) sizeof(ev)) continue;
        handle_ff_event(slot, s->fd, &ev);
    }
    return NULL;
}

static int open_slot(int slot, const char *name) {
    if (slot < 0 || slot >= MAX_SLOTS) return 0;
    if (g_slots[slot].fd >= 0) return 1;

    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        out("L open /dev/uinput failed: %s", strerror(errno));
        return 0;
    }
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_FF);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    static const int keys[] = {
        BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_THUMBL, BTN_THUMBR,
        BTN_START, BTN_MODE, KEY_BACK, KEY_HOMEPAGE,
    };
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) ioctl(fd, UI_SET_KEYBIT, keys[i]);

    static const int axes[] = { ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y };
    for (size_t i = 0; i < sizeof(axes) / sizeof(axes[0]); i++) ioctl(fd, UI_SET_ABSBIT, axes[i]);

    ioctl(fd, UI_SET_FFBIT, FF_RUMBLE);

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    dev.id.bustype = BUS_USB;
    dev.id.vendor = 0x045e;  // Xbox One S layout, matches a stock .kl file
    dev.id.product = 0x02fd;
    dev.id.version = 1;
    dev.ff_effects_max = MAX_FF_EFFECTS;
    dev.absmin[ABS_X] = -32768; dev.absmax[ABS_X] = 32767;
    dev.absmin[ABS_Y] = -32768; dev.absmax[ABS_Y] = 32767;
    dev.absmin[ABS_Z] = -32768; dev.absmax[ABS_Z] = 32767;
    dev.absmin[ABS_RZ] = -32768; dev.absmax[ABS_RZ] = 32767;
    dev.absmin[ABS_GAS] = 0; dev.absmax[ABS_GAS] = 1023;
    dev.absmin[ABS_BRAKE] = 0; dev.absmax[ABS_BRAKE] = 1023;
    dev.absmin[ABS_HAT0X] = -1; dev.absmax[ABS_HAT0X] = 1;
    dev.absmin[ABS_HAT0Y] = -1; dev.absmax[ABS_HAT0Y] = 1;

    if (write(fd, &dev, sizeof(dev)) < 0) {
        out("L write uinput_user_dev failed: %s", strerror(errno));
        close(fd);
        return 0;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        out("L UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        return 0;
    }

    slot_t *s = &g_slots[slot];
    memset(s->effects, 0, sizeof(s->effects));
    s->fd = fd;
    s->running = 1;
    s->has_thread = pthread_create(&s->ff_thread, NULL, ff_thread_main, (void *) (long) slot) == 0;
    out("L uinput gamepad created: slot=%d fd=%d name='%s'", slot, fd, name);
    return 1;
}

static void close_slot(int slot) {
    if (slot < 0 || slot >= MAX_SLOTS) return;
    slot_t *s = &g_slots[slot];
    if (s->fd < 0) return;
    s->running = 0;
    if (s->has_thread) pthread_join(s->ff_thread, NULL);
    s->has_thread = 0;
    ioctl(s->fd, UI_DEV_DESTROY);
    close(s->fd);
    s->fd = -1;
    out("L uinput gamepad destroyed: slot=%d", slot);
}

static int slot_fd(int slot) {
    if (slot < 0 || slot >= MAX_SLOTS) return -1;
    return g_slots[slot].fd;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    for (int i = 0; i < MAX_SLOTS; i++) { g_slots[i].fd = -1; g_slots[i].running = 0; g_slots[i].has_thread = 0; }
    setvbuf(stdin, NULL, _IOLBF, 0);

    out("L gipuinputd ready, uid=%d", (int) getuid());
    out("R READY");

    char line[512];
    while (fgets(line, sizeof(line), stdin) != NULL) {
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = 0;
        if (len == 0) continue;

        char cmd = line[0];
        if (cmd == 'A') {
            int slot, x, y, z, rz, gas, brake, hx, hy;
            if (sscanf(line + 1, "%d %d %d %d %d %d %d %d %d", &slot, &x, &y, &z, &rz, &gas, &brake, &hx, &hy) == 9) {
                int fd = slot_fd(slot);
                if (fd >= 0) {
                    write_event(fd, EV_ABS, ABS_X, x);
                    write_event(fd, EV_ABS, ABS_Y, y);
                    write_event(fd, EV_ABS, ABS_Z, z);
                    write_event(fd, EV_ABS, ABS_RZ, rz);
                    write_event(fd, EV_ABS, ABS_GAS, gas);
                    write_event(fd, EV_ABS, ABS_BRAKE, brake);
                    write_event(fd, EV_ABS, ABS_HAT0X, hx);
                    write_event(fd, EV_ABS, ABS_HAT0Y, hy);
                    write_event(fd, EV_SYN, SYN_REPORT, 0);
                }
            }
        } else if (cmd == 'K') {
            int slot, code, value;
            if (sscanf(line + 1, "%d %d %d", &slot, &code, &value) == 3) {
                int fd = slot_fd(slot);
                if (fd >= 0) {
                    write_event(fd, EV_KEY, (unsigned short) code, value);
                    write_event(fd, EV_SYN, SYN_REPORT, 0);
                }
            }
        } else if (cmd == 'O') {
            int slot = -1, consumed = 0;
            if (sscanf(line + 1, "%d %n", &slot, &consumed) >= 1) {
                const char *name = line + 1 + consumed;
                if (*name == 0) name = "GIP Bridge Gamepad";
                out("R O %d %d", slot, open_slot(slot, name));
            }
        } else if (cmd == 'C') {
            int slot;
            if (sscanf(line + 1, "%d", &slot) == 1) close_slot(slot);
        } else if (cmd == 'P') {
            out("R P");
        } else if (cmd == 'Q') {
            break;
        }
    }

    for (int i = 0; i < MAX_SLOTS; i++) close_slot(i);
    out("L gipuinputd exit");
    return 0;
}
