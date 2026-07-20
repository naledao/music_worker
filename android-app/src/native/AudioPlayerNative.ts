import { NativeEventEmitter, NativeModules, Platform } from "react-native";

export type AudioPlayerStatusEvent = {
  url?: string | null;
  title?: string | null;
  isPlaying: boolean;
  isBuffering: boolean;
  isPrepared: boolean;
  ended: boolean;
  positionSec: number;
  durationSec: number;
  message?: string | null;
  errorMessage?: string | null;
};

type AndroidAudioPlayerModule = {
  play(
    url: string,
    title?: string | null,
    durationSec?: number
  ): Promise<AudioPlayerStatusEvent>;
  pause(): Promise<AudioPlayerStatusEvent>;
  resume(): Promise<AudioPlayerStatusEvent>;
  seekBy(deltaSec: number): Promise<AudioPlayerStatusEvent>;
  stop(): Promise<AudioPlayerStatusEvent>;
  getStatus(): Promise<AudioPlayerStatusEvent>;
};

const nativeModule = NativeModules.AndroidAudioPlayer as
  | AndroidAudioPlayerModule
  | undefined;

const eventEmitter =
  Platform.OS === "android" && nativeModule
    ? new NativeEventEmitter(NativeModules.AndroidAudioPlayer)
    : null;

function requireAndroidAudioPlayer(): AndroidAudioPlayerModule {
  if (!nativeModule) {
    throw new Error("Android 音频播放模块不可用");
  }
  return nativeModule;
}

export function playAudio(params: {
  url: string;
  title?: string | null;
  durationSec?: number | null;
}): Promise<AudioPlayerStatusEvent> {
  return requireAndroidAudioPlayer().play(
    params.url,
    params.title ?? null,
    params.durationSec ?? 0
  );
}

export function pauseAudio(): Promise<AudioPlayerStatusEvent> {
  return requireAndroidAudioPlayer().pause();
}

export function resumeAudio(): Promise<AudioPlayerStatusEvent> {
  return requireAndroidAudioPlayer().resume();
}

export function seekAudioBy(deltaSec: number): Promise<AudioPlayerStatusEvent> {
  return requireAndroidAudioPlayer().seekBy(deltaSec);
}

export function stopAudio(): Promise<AudioPlayerStatusEvent> {
  return requireAndroidAudioPlayer().stop();
}

export function getAudioStatus(): Promise<AudioPlayerStatusEvent> {
  return requireAndroidAudioPlayer().getStatus();
}

export function addAudioPlayerStatusListener(
  listener: (event: AudioPlayerStatusEvent) => void
): () => void {
  if (!eventEmitter) {
    return () => {};
  }
  const subscription = eventEmitter.addListener(
    "AndroidAudioPlayerStatus",
    listener
  );
  return () => subscription.remove();
}
