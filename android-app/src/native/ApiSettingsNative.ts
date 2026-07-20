import { NativeEventEmitter, NativeModules, Platform } from "react-native";

export type PersistedApiConfig = {
  host: string;
  port: string;
};

export type PersistedDownloadDirectory = {
  uri: string | null;
  label: string | null;
};

export type SavedDownloadFile = {
  fileName: string;
  fileUri: string;
  fileSize: number;
};

export type FileSaveProgressEvent = {
  musicId?: string | null;
  downloadedBytes: number;
  totalBytes?: number | null;
  progress: number;
};

type AndroidSettingsModule = {
  getApiConfig(): Promise<PersistedApiConfig>;
  saveApiConfig(host: string, port: string): Promise<PersistedApiConfig>;
  getDownloadDirectory(): Promise<PersistedDownloadDirectory>;
  pickDownloadDirectory(): Promise<PersistedDownloadDirectory>;
  clearDownloadDirectory(): Promise<PersistedDownloadDirectory>;
  saveUrlToDownloadDirectory(
    url: string,
    fileName?: string | null,
    musicId?: string | null
  ): Promise<SavedDownloadFile>;
};

const nativeModule = NativeModules.AndroidSettings as
  | AndroidSettingsModule
  | undefined;

const eventEmitter =
  Platform.OS === "android" && nativeModule
    ? new NativeEventEmitter(NativeModules.AndroidSettings)
    : null;

export async function loadPersistedApiConfig(): Promise<PersistedApiConfig | null> {
  if (Platform.OS !== "android" || !nativeModule) {
    return null;
  }
  return nativeModule.getApiConfig();
}

export async function savePersistedApiConfig(
  config: PersistedApiConfig
): Promise<PersistedApiConfig> {
  if (Platform.OS !== "android" || !nativeModule) {
    return config;
  }
  return nativeModule.saveApiConfig(config.host, config.port);
}

export async function loadPersistedDownloadDirectory(): Promise<PersistedDownloadDirectory | null> {
  if (Platform.OS !== "android" || !nativeModule) {
    return null;
  }
  return nativeModule.getDownloadDirectory();
}

export async function pickPersistedDownloadDirectory(): Promise<PersistedDownloadDirectory> {
  if (Platform.OS !== "android" || !nativeModule) {
    return { uri: null, label: null };
  }
  return nativeModule.pickDownloadDirectory();
}

export async function clearPersistedDownloadDirectory(): Promise<PersistedDownloadDirectory> {
  if (Platform.OS !== "android" || !nativeModule) {
    return { uri: null, label: null };
  }
  return nativeModule.clearDownloadDirectory();
}

export async function saveUrlToPersistedDownloadDirectory(params: {
  url: string;
  fileName?: string | null;
  musicId?: string | null;
}): Promise<SavedDownloadFile> {
  if (Platform.OS !== "android" || !nativeModule) {
    throw new Error("Android 下载目录模块不可用");
  }
  return nativeModule.saveUrlToDownloadDirectory(
    params.url,
    params.fileName ?? null,
    params.musicId ?? null
  );
}

export function addFileSaveProgressListener(
  listener: (event: FileSaveProgressEvent) => void
): () => void {
  if (!eventEmitter) {
    return () => {};
  }
  const subscription = eventEmitter.addListener(
    "AndroidSettingsFileSaveProgress",
    listener
  );
  return () => subscription.remove();
}
