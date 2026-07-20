import { NativeEventEmitter, NativeModules, Platform } from "react-native";

export type InstalledAppInfo = {
  versionName: string;
  versionCode: number;
};

export type UpdateProgressEvent = {
  downloadedBytes: number;
  totalBytes?: number;
  progress: number;
};

export type InstallResult = {
  status: "installer_started" | "permission_required";
  message: string;
  uri?: string;
};

type AndroidUpdateModule = {
  getInstalledAppInfo(): Promise<InstalledAppInfo>;
  downloadAndInstall(
    downloadUrl: string,
    fileName: string,
    expectedSha256?: string | null
  ): Promise<InstallResult>;
};

const nativeModule = NativeModules.AndroidUpdate as
  | AndroidUpdateModule
  | undefined;

const eventEmitter =
  Platform.OS === "android" && nativeModule
    ? new NativeEventEmitter(NativeModules.AndroidUpdate)
    : null;

function requireAndroidUpdate(): AndroidUpdateModule {
  if (!nativeModule) {
    throw new Error("Android 更新模块不可用");
  }
  return nativeModule;
}

export async function getInstalledAppInfo(): Promise<InstalledAppInfo> {
  return requireAndroidUpdate().getInstalledAppInfo();
}

export async function downloadAndInstallUpdate(params: {
  downloadUrl: string;
  fileName: string;
  expectedSha256?: string | null;
}): Promise<InstallResult> {
  return requireAndroidUpdate().downloadAndInstall(
    params.downloadUrl,
    params.fileName,
    params.expectedSha256 ?? null
  );
}

export function addUpdateProgressListener(
  listener: (event: UpdateProgressEvent) => void
): () => void {
  if (!eventEmitter) {
    return () => {};
  }
  const subscription = eventEmitter.addListener(
    "AndroidUpdateProgress",
    listener
  );
  return () => subscription.remove();
}
