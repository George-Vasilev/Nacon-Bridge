# NACON Bridge

**Play with your wired Xbox One / Series–style controller on an Android phone, even when the phone does not support it. No root needed.**

Many licensed "for Xbox" wired controllers (NACON, PDP, PowerA, Hori, …) stay dark when you plug them into a phone. The phone's kernel has no driver entry for them, so nobody sends the start-up packet. NACON Bridge does it: it wakes the controller up and turns it into a normal Android gamepad that every app can use, including GeForce NOW, emulators and Android games. Rumble works too.

[🇧🇬 Български по-долу](#-български)

## What you need

- A **USB‑C OTG adapter** to plug the controller into the phone.
- The **[Shizuku](https://github.com/RikkaApps/Shizuku/releases)** app (free). It is required: without root there is no other way to create a gamepad that other apps can see.
- **Start Shizuku with Wireless debugging.** This needs Wi‑Fi. Shizuku's own guide shows how, and you pair only once.
- Once Shizuku is running, you can **switch to mobile data at any time**.
- **After every phone restart Shizuku stops.** Start it again while on any Wi‑Fi (internet not needed). Shizuku 13.6+ can start by itself on a trusted Wi‑Fi.
- The first time you plug in the controller: tick **"Always open"** and tap **OK**.

## Install

1. Download `NACON-Bridge-<version>.apk` from [Releases](../../releases) and install it.
2. Start Shizuku.
3. Open NACON Bridge and allow it in Shizuku when asked.
4. Plug in the controller. It lights up, and the app shows **"All set – you can play."**

The app has English and Bulgarian (EN / BG button at the top).

## Which controllers work

- **Wired controllers that use the Xbox One / Series protocol (GIP).** Tested: **NACON Pro Compact** on a Nothing Phone 2a (Android 16). Others with the same protocol should work, and the app sends the same brand-specific start-up packets as Linux `xpad` (PDP, PowerA, Hori, Microsoft).
- If your phone already supports a controller by itself, the app leaves it alone.
- **Not supported:** Bluetooth controllers, the Xbox Wireless Adapter, Xbox 360 controllers (most phones support those already), PlayStation and Nintendo controllers.

Did your controller work, or not? Please open an issue with the controller name, the phone and the app log (**Show log** button).

## How it works

1. Android opens the app when a USB device with a GIP interface (class `0xFF`, subclass `0x47`, protocol `0xD0`) is plugged in.
2. The app claims that interface through the normal Android USB Host API and speaks GIP to the controller. It sends POWER ON, acknowledges reports and reads input. Rumble packets go back the same way.
3. A small native program (`gipuinputd`) runs as the shell user through Shizuku and creates a real Linux **uinput** gamepad. It uses the Xbox One S layout (`045e:02fd`), so Android's built-in key layout file maps every button correctly.

Why Shizuku is needed: a normal app may not create input devices for other apps. Only the shell user (ADB) or root can open `/dev/uinput`.

Why a separate program instead of a Shizuku "user service": on many MediaTek phones with Android 16, Shizuku user services cannot start ([Shizuku #1198](https://github.com/RikkaApps/Shizuku/issues/1198)). A plain process started through Shizuku works everywhere.

## Build

Requires JDK 17, Android SDK 35, NDK 27.0.12077973 and CMake 3.22.1.

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## Credits and license

- Made by **GeorgeTheFirst** with **Claude (Anthropic)**, which wrote and debugged the code together with him.
- Based on **[GIP Bridge](https://github.com/BoredNewCoder/8bitdo-xbox-bridge)** by Christopher VanZetta (MIT).
- The GIP protocol details come from the Linux drivers [`xone`](https://github.com/medusalix/xone) and `xpad`.
- Uses [Shizuku](https://github.com/RikkaApps/Shizuku).
- License: MIT (see [LICENSE](LICENSE)).

"Xbox" is a trademark of Microsoft. "NACON" is a trademark of NACON. This project is not affiliated with Microsoft or NACON.

---

## 🇧🇬 Български

**Играй с жичен контролер в стил Xbox One / Series на Android телефон, дори когато телефонът не го поддържа. Без root.**

Много жични контролери „for Xbox“ (NACON, PDP, PowerA, Hori…) не светват, когато ги включиш в телефон: в ядрото на телефона няма драйвер за тях. NACON Bridge „събужда“ контролера и го превръща в нормален геймпад, който всички приложения виждат: GeForce NOW, емулатори, игри. Вибрацията също работи.

### Какво ти трябва

- **USB‑C OTG адаптер**, за да включиш контролера в телефона.
- Приложението **[Shizuku](https://github.com/RikkaApps/Shizuku/releases)** (безплатно). Задължително е: без root няма друг начин.
- **Стартирай Shizuku чрез Wireless debugging** (Безжично отстраняване на грешки). Трябва Wi‑Fi. В самия Shizuku има упътване; сдвояването се прави само веднъж.
- Щом Shizuku работи, можеш **да минеш на мобилни данни по всяко време**.
- **След всеки рестарт на телефона Shizuku спира.** Стартирай го пак, докато си на някакъв Wi‑Fi (не е нужен интернет). Shizuku 13.6+ може да тръгва сам на доверен Wi‑Fi.
- Първия път, когато включиш контролера: сложи отметка **„Винаги“** и натисни **OK**.

### Инсталиране

1. Изтегли `NACON-Bridge-<версия>.apk` от [Releases](../../releases) и го инсталирай.
2. Стартирай Shizuku.
3. Отвори NACON Bridge и го разреши в Shizuku, когато те попита.
4. Включи контролера. Той светва и приложението показва **„Всичко е готово – можеш да играеш.“**

Приложението е на английски и български (бутон EN / BG горе).

### Кои контролери работят

- **Жични контролери с Xbox One / Series протокол (GIP).** Тествано с **NACON Pro Compact** на Nothing Phone 2a (Android 16). Други със същия протокол би трябвало да работят.
- Ако телефонът ти вече поддържа даден контролер сам, приложението не го пипа.
- **Не се поддържат:** Bluetooth контролери, Xbox Wireless Adapter, Xbox 360 контролери, PlayStation и Nintendo.

Направено от **GeorgeTheFirst** с **Claude (Anthropic)**. Базирано на GIP Bridge от Christopher VanZetta (MIT).
