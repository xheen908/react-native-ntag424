# react-native-ntag424-native

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg?style=for-the-badge&logo=android" alt="Platform" />
  <img src="https://img.shields.io/badge/Expo-Modules-000000.svg?style=for-the-badge&logo=expo" alt="Expo Modules" />
  <img src="https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=for-the-badge&logo=kotlin" alt="Kotlin" />
</p>

A powerful Expo Native Module for Android to interact directly with **NTAG 424 DNA** microchips. This module provides low-level native capabilities to perform secure operations such as initializing tags, reading SUN (Secure Unique NFC) proofs, and running continuous auto-flash/auto-reset processes using the device's NFC reader.

---

## 🚀 Features

- **Tag Initialization:** Configure and lock NTAG 424 DNA tags using a master key and base URL.
- **SUN Proof Generation:** Retrieve PICC Data, CMAC, and URL paths natively for cryptographic validation.
- **Auto-Flash Mode:** Streamlined continuous flashing for high-volume tag setups.
- **Auto-Reset Mode:** Fast, automated restoration of tags back to their default state.
- **Native Efficiency:** Built entirely using Kotlin on top of the modern **Expo Modules API**.

---

## 📦 Installation

Since this package is hosted on GitHub, you can install it directly using its repository URL:

```bash
# Add directly to your Expo project from GitHub
npx expo install https://github.com/xheen908/react-native-ntag424-native.git

# Or using npm / yarn / pnpm
npm install https://github.com/xheen908/react-native-ntag424-native.git
```


### Configuration

Ensure NFC permissions are included in your `app.json` configuration:

```json
{
  "expo": {
    "android": {
      "permissions": [
        "android.permission.NFC"
      ]
    }
  }
}
```

---

## 🛠️ API Reference

```typescript
import { 
  initializeTag, 
  startAutoFlash, 
  stopAutoFlash, 
  startAutoReset, 
  stopAutoReset, 
  stopNfcReader, 
  getSunProof,
  emitter 
} from 'react-native-ntag424-native';
```

### Methods

#### `initializeTag`
Initializes a new NTAG 424 DNA tag with a specified master key and base URL.
```typescript
async function initializeTag(
  masterKey: string, 
  baseUrl: string
): Promise<{ success: boolean; uid?: string }>
```

#### `getSunProof`
Reads the tag to generate and retrieve the SUN (Secure Unique NFC) proof.
```typescript
async function getSunProof(): Promise<{ 
  piccData: string; 
  cmac: string; 
  urlPath: string; 
}>
```

#### `startAutoFlash`
Starts the continuous background reader to automatically flash any NTAG 424 DNA tag brought near the device.
```typescript
function startAutoFlash(masterKey: string, baseUrl: string): void
```

#### `stopAutoFlash`
Stops the continuous auto-flashing service.
```typescript
function stopAutoFlash(): void
```

#### `startAutoReset`
Starts the continuous background reader to automatically reset any NTAG 424 DNA tag brought near the device.
```typescript
function startAutoReset(masterKey: string): void
```

#### `stopAutoReset`
Stops the continuous auto-reset service.
```typescript
function stopAutoReset(): void
```

#### `stopNfcReader`
Stops any active native NFC reader operations.
```typescript
function stopNfcReader(): void
```

---

## 📡 Events

You can listen to native events emitted by the module using the provided `emitter` or `NativeEventEmitter`:

```typescript
import { emitter } from 'react-native-ntag424-native';

useEffect(() => {
  const subscription = emitter.addListener('onTagDetected', (event) => {
    console.log('Tag detected natively:', event);
  });

  return () => {
    subscription.remove();
  };
}, []);
```

---

## 🛡️ License

This project is licensed under the MIT License.
