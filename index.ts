import { NativeModulesProxy, EventEmitter, Subscription } from 'expo-modules-core';

// Import the native module. On web, it will be null.
import VLedgerNtagModule from './src/VLedgerNtagModule';

export async function initializeTag(masterKey: string, baseUrl: string): Promise<{ success: boolean; uid?: string }> {
  return await VLedgerNtagModule.initializeTag(masterKey, baseUrl);
}

export function startAutoFlash(masterKey: string, baseUrl: string) {
  return VLedgerNtagModule.startAutoFlash(masterKey, baseUrl);
}

export function stopAutoFlash() {
  return VLedgerNtagModule.stopAutoFlash();
}

export function startAutoReset(masterKey: string) {
  return VLedgerNtagModule.startAutoReset(masterKey);
}

export function stopAutoReset() {
  return VLedgerNtagModule.stopAutoReset();
}

export function stopNfcReader() {
  return VLedgerNtagModule.stopNfcReader();
}

export async function getSunProof(): Promise<{ piccData: string, cmac: string, urlPath: string }> {
  return await VLedgerNtagModule.getSunProof();
}

export const emitter = new EventEmitter(VLedgerNtagModule);

export { VLedgerNtagModule, EventEmitter as NativeEventEmitter };
