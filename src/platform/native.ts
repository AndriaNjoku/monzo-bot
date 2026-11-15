import { NativeModules, Platform } from 'react-native';

export type ProviderId = 'openai' | 'anthropic';
export type NotificationOptions = {
  enabled: boolean;
  persistent: boolean;
  quietStart: number;
  quietEnd: number;
};
export type NotificationStatus = NotificationOptions & {
  permission: boolean;
  channelEnabled: boolean;
  delivery: string;
};
type AndroidDevice = {
  providerRequest(
    provider: ProviderId,
    key: string,
    bodyJson: string,
  ): Promise<string>;
  googleRequest(url: string, token: string): Promise<string>;
  loadWorkspace(): Promise<string | null>;
  saveWorkspace(json: string): Promise<void>;
  readProviderKey(provider: ProviderId): Promise<string>;
  saveProviderKey(provider: ProviderId, key: string): Promise<void>;
  forgetProviderKey(provider: ProviderId | 'all'): Promise<void>;
  deleteWorkspace(): Promise<void>;
  authorizeGoogle(): Promise<{ accessToken: string }>;
  disconnectGoogle(): Promise<void>;
  recognizeSpeech(): Promise<string>;
  cancelSpeech(): Promise<void>;
  notificationStatus(): Promise<NotificationStatus>;
  configureNotifications(options: NotificationOptions): Promise<void>;
  openCalendarDraft(
    title: string,
    day: string,
    description: string,
  ): Promise<void>;
  importWorkspace(): Promise<string>;
  exportWorkspace(): Promise<void>;
};
function bridge(): AndroidDevice {
  if (Platform.OS !== 'android' || !NativeModules.MonzoDevice) {
    throw new Error(
      'This private device integration is implemented for Android. Other platforms need their own secure integration; no insecure fallback is used.',
    );
  }
  return NativeModules.MonzoDevice as AndroidDevice;
}
/** Keys and Google tokens never enter financial JSON. Every rejected native operation preserves existing data. */
export const device: AndroidDevice = {
  providerRequest: (provider, key, bodyJson) =>
    bridge().providerRequest(provider, key, bodyJson),
  googleRequest: (url, token) => bridge().googleRequest(url, token),
  loadWorkspace: () => bridge().loadWorkspace(),
  saveWorkspace: json => bridge().saveWorkspace(json),
  readProviderKey: provider => bridge().readProviderKey(provider),
  saveProviderKey: (provider, key) => bridge().saveProviderKey(provider, key),
  forgetProviderKey: provider => bridge().forgetProviderKey(provider),
  deleteWorkspace: () => bridge().deleteWorkspace(),
  authorizeGoogle: () => bridge().authorizeGoogle(),
  disconnectGoogle: () => bridge().disconnectGoogle(),
  recognizeSpeech: () => bridge().recognizeSpeech(),
  cancelSpeech: () => bridge().cancelSpeech(),
  notificationStatus: () => bridge().notificationStatus(),
  configureNotifications: options => bridge().configureNotifications(options),
  openCalendarDraft: (title, day, description) =>
    bridge().openCalendarDraft(title, day, description),
  importWorkspace: () => bridge().importWorkspace(),
  exportWorkspace: () => bridge().exportWorkspace(),
};
export const {
  providerRequest,
  googleRequest,
  loadWorkspace,
  saveWorkspace,
  readProviderKey,
  saveProviderKey,
  forgetProviderKey,
  deleteWorkspace,
  authorizeGoogle,
  disconnectGoogle,
  recognizeSpeech,
  cancelSpeech,
  notificationStatus,
  configureNotifications,
  openCalendarDraft,
  importWorkspace,
  exportWorkspace,
} = device;
