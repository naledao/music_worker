import type {
  ChartItem,
  ChartSourceInfo,
  DownloadTask,
  DownloadedSongItem,
  HealthPayload,
  SearchItem,
} from "../api/types";

export type MockSearchItem = SearchItem & {
  lyrics?: string;
};

export type MockChartItem = ChartItem & {
  musicId: string;
};

export type MockDownloadedSong = DownloadedSongItem & {
  cover?: string | null;
  lyricsContent?: string | null;
};

export type MockTask = DownloadTask & {
  title?: string;
  cover?: string | null;
};

const now = new Date("2026-06-15T09:20:00Z");

export const mockHealth: HealthPayload = {
  service: {
    name: "music-worker",
    host: "127.0.0.1",
    port: 18081,
  },
  runtime: {
    cwd: "/root/codes/music_worker",
    baseDir: "/root/codes/music_worker",
    cookies: {
      file: "/root/codes/music_worker/cookies.txt",
      exists: true,
      size: 19328,
      hasYoutube: true,
      enabled: true,
    },
    proxy: {
      ytdlp: "http://127.0.0.1:7890",
      ws: "socks5://127.0.0.1:7890",
    },
    ytDlp: {
      version: "2026.06.01",
      path: "/usr/local/bin/yt-dlp",
      jsRuntime: "node",
      remoteComponents: "enabled",
      playerClients: ["android_music", "web_music"],
      fetchPot: "bgutil",
      potTrace: false,
      pluginDir: "yt-dlp-plugins",
    },
    ffmpeg: "/usr/bin/ffmpeg",
  },
  tasks: {
    total: 18,
    queued: 1,
    running: 2,
    finished: 14,
    failed: 1,
  },
  proxy: {
    selector: "Proxy",
    name: "HK-01",
    alive: true,
    options: ["HK-01", "JP-02", "SG-01", "US-West", "DIRECT"],
  },
};

export const mockSearchItems: MockSearchItem[] = [
  {
    id: "yt-aura-night-drive",
    title: "Aura - Night Drive",
    channel: "Northern Lights Studio",
    duration: 246,
    cover: "https://picsum.photos/seed/night-drive/320/320",
    downloaded: true,
    downloadedFilePath: "/sdcard/Music/Aura - Night Drive.mp3",
    downloadedFileSize: 9437184,
    downloadedAt: "2026-06-14T20:18:00Z",
    lyrics: `[00:00.00]Aura - Night Drive
[00:12.00]City lights are leaning into rain
[00:24.00]Every turn remembers your name
[00:38.00]I keep the signal clear tonight
[00:52.00]Driving through the blue and white`,
  },
  {
    id: "yt-lena-sunrise-line",
    title: "Lena Vale - Sunrise Line",
    channel: "Indigo Records",
    duration: 213,
    cover: "https://picsum.photos/seed/sunrise-line/320/320",
    downloaded: false,
  },
  {
    id: "yt-kai-river-static",
    title: "Kai Rowan - River Static",
    channel: "Kite Room",
    duration: 197,
    cover: "https://picsum.photos/seed/river-static/320/320",
    downloaded: false,
  },
  {
    id: "yt-mira-soft-pulse",
    title: "Mira Chen - Soft Pulse",
    channel: "Paper Moon",
    duration: 268,
    cover: "https://picsum.photos/seed/soft-pulse/320/320",
    downloaded: true,
    downloadedFilePath: "/sdcard/Music/Mira Chen - Soft Pulse.mp3",
    downloadedFileSize: 11010048,
    downloadedAt: "2026-06-13T18:36:00Z",
  },
  {
    id: "yt-oxbow-starlit-room",
    title: "Oxbow - Starlit Room",
    channel: "Wide Angle Audio",
    duration: 231,
    cover: "https://picsum.photos/seed/starlit-room/320/320",
    downloaded: false,
  },
  {
    id: "yt-echo-silver-thread",
    title: "Echo Lane - Silver Thread",
    channel: "Low Tide Music",
    duration: 288,
    cover: "https://picsum.photos/seed/silver-thread/320/320",
    downloaded: false,
  },
  {
    id: "yt-violet-small-hours",
    title: "Violet Arc - Small Hours",
    channel: "Violet Arc",
    duration: 204,
    cover: "https://picsum.photos/seed/small-hours/320/320",
    downloaded: false,
  },
  {
    id: "yt-nova-clear-signal",
    title: "Nova Park - Clear Signal",
    channel: "Signal House",
    duration: 256,
    cover: "https://picsum.photos/seed/clear-signal/320/320",
    downloaded: false,
  },
  {
    id: "yt-arden-glass-fields",
    title: "Arden - Glass Fields",
    channel: "North Gate",
    duration: 219,
    cover: "https://picsum.photos/seed/glass-fields/320/320",
    downloaded: false,
  },
  {
    id: "yt-sora-afterimage",
    title: "Sora Vale - Afterimage",
    channel: "Night Market",
    duration: 242,
    cover: "https://picsum.photos/seed/afterimage/320/320",
    downloaded: false,
  },
  {
    id: "yt-helio-quiet-current",
    title: "Helio - Quiet Current",
    channel: "Blue Room Sessions",
    duration: 301,
    cover: "https://picsum.photos/seed/quiet-current/320/320",
    downloaded: false,
  },
  {
    id: "yt-sable-paper-sky",
    title: "Sable - Paper Sky",
    channel: "Small Batch Audio",
    duration: 224,
    cover: "https://picsum.photos/seed/paper-sky/320/320",
    downloaded: false,
  },
];

export const chartSources: ChartSourceInfo[] = [
  {
    id: "apple_music",
    label: "Apple Music",
    types: ["songs"],
    periods: ["daily"],
    regions: [
      { id: "us", label: "United States" },
      { id: "jp", label: "Japan" },
      { id: "hk", label: "Hong Kong" },
      { id: "gb", label: "United Kingdom" },
      { id: "tw", label: "Taiwan" },
    ],
  },
];

export const mockChartItems: MockChartItem[] = [
  {
    rank: 1,
    musicId: "chart-lena-sunrise-line",
    title: "Sunrise Line",
    artist: "Lena Vale",
    cover: "https://picsum.photos/seed/chart-sunrise/320/320",
    album: "Open Road",
    durationSec: 213,
    deeplink: "music://charts/sunrise-line",
    searchKeyword: "Lena Vale Sunrise Line",
    sourceId: "apple_music",
    releaseDate: "2026-06-07",
  },
  {
    rank: 2,
    musicId: "chart-aura-night-drive",
    title: "Night Drive",
    artist: "Aura",
    cover: "https://picsum.photos/seed/chart-night-drive/320/320",
    album: "Cities After Rain",
    durationSec: 246,
    searchKeyword: "Aura Night Drive",
    sourceId: "apple_music",
    releaseDate: "2026-05-30",
  },
  {
    rank: 3,
    musicId: "chart-nova-clear-signal",
    title: "Clear Signal",
    artist: "Nova Park",
    cover: "https://picsum.photos/seed/chart-clear-signal/320/320",
    album: "Clear Signal",
    durationSec: 256,
    searchKeyword: "Nova Park Clear Signal",
    sourceId: "apple_music",
    releaseDate: "2026-06-01",
  },
  {
    rank: 4,
    musicId: "chart-violet-small-hours",
    title: "Small Hours",
    artist: "Violet Arc",
    cover: "https://picsum.photos/seed/chart-small-hours/320/320",
    album: "Small Hours",
    durationSec: 204,
    searchKeyword: "Violet Arc Small Hours",
    sourceId: "apple_music",
  },
  {
    rank: 5,
    musicId: "chart-mira-soft-pulse",
    title: "Soft Pulse",
    artist: "Mira Chen",
    cover: "https://picsum.photos/seed/chart-soft-pulse/320/320",
    album: "Room Tone",
    durationSec: 268,
    searchKeyword: "Mira Chen Soft Pulse",
    sourceId: "apple_music",
  },
  {
    rank: 6,
    musicId: "chart-kai-river-static",
    title: "River Static",
    artist: "Kai Rowan",
    cover: "https://picsum.photos/seed/chart-river-static/320/320",
    album: "River Static",
    durationSec: 197,
    searchKeyword: "Kai Rowan River Static",
    sourceId: "apple_music",
  },
  {
    rank: 7,
    musicId: "chart-echo-silver-thread",
    title: "Silver Thread",
    artist: "Echo Lane",
    cover: "https://picsum.photos/seed/chart-silver-thread/320/320",
    album: "Low Tide",
    durationSec: 288,
    searchKeyword: "Echo Lane Silver Thread",
    sourceId: "apple_music",
  },
  {
    rank: 8,
    musicId: "chart-sora-afterimage",
    title: "Afterimage",
    artist: "Sora Vale",
    cover: "https://picsum.photos/seed/chart-afterimage/320/320",
    album: "Afterimage",
    durationSec: 242,
    searchKeyword: "Sora Vale Afterimage",
    sourceId: "apple_music",
  },
];

export const mockDownloadedSongs: MockDownloadedSong[] = [
  {
    musicId: "yt-aura-night-drive",
    filePath: "/sdcard/Music/Aura - Night Drive.mp3",
    filename: "Aura - Night Drive.mp3",
    displayTitle: "Aura - Night Drive",
    fileSize: 9437184,
    durationSec: 246,
    downloadedAt: "2026-06-14T20:18:00Z",
    updatedAt: "2026-06-14T20:18:00Z",
    lyricsPath: "/sdcard/Music/Aura - Night Drive.lrc",
    lyricsExists: true,
    lyricsUpdatedAt: "2026-06-14T20:24:00Z",
    cover: "https://picsum.photos/seed/night-drive/320/320",
    lyricsContent: `[00:00.00]Aura - Night Drive
[00:12.00]City lights are leaning into rain
[00:24.00]Every turn remembers your name
[00:38.00]I keep the signal clear tonight
[00:52.00]Driving through the blue and white
[01:05.00]No one sleeps under this skyline
[01:20.00]We keep rolling past the signs`,
  },
  {
    musicId: "yt-mira-soft-pulse",
    filePath: "/sdcard/Music/Mira Chen - Soft Pulse.mp3",
    filename: "Mira Chen - Soft Pulse.mp3",
    displayTitle: "Mira Chen - Soft Pulse",
    fileSize: 11010048,
    durationSec: 268,
    downloadedAt: "2026-06-13T18:36:00Z",
    updatedAt: "2026-06-13T18:36:00Z",
    lyricsExists: false,
    cover: "https://picsum.photos/seed/soft-pulse/320/320",
  },
  {
    musicId: "yt-arden-glass-fields",
    filePath: "/sdcard/Music/Arden - Glass Fields.mp3",
    filename: "Arden - Glass Fields.mp3",
    displayTitle: "Arden - Glass Fields",
    fileSize: 8781824,
    durationSec: 219,
    downloadedAt: "2026-06-12T12:15:00Z",
    updatedAt: "2026-06-12T12:15:00Z",
    lyricsExists: false,
    cover: "https://picsum.photos/seed/glass-fields/320/320",
  },
];

export const mockTasks: MockTask[] = [
  {
    taskId: "task-20260614-night-drive",
    type: "download",
    musicId: "yt-aura-night-drive",
    title: "Aura - Night Drive",
    status: "finished",
    stage: "completed",
    progress: 100,
    createdAt: "2026-06-14T20:13:00Z",
    updatedAt: "2026-06-14T20:18:00Z",
    filename: "Aura - Night Drive.mp3",
    filePath: "/sdcard/Music/Aura - Night Drive.mp3",
    fileSize: 9437184,
    downloadedBytes: 9437184,
    totalBytes: 9437184,
    speedBps: 0,
    cover: "https://picsum.photos/seed/night-drive/320/320",
  },
  {
    taskId: "task-20260613-soft-pulse",
    type: "download",
    musicId: "yt-mira-soft-pulse",
    title: "Mira Chen - Soft Pulse",
    status: "finished",
    stage: "completed",
    progress: 100,
    createdAt: "2026-06-13T18:29:00Z",
    updatedAt: "2026-06-13T18:36:00Z",
    filename: "Mira Chen - Soft Pulse.mp3",
    filePath: "/sdcard/Music/Mira Chen - Soft Pulse.mp3",
    fileSize: 11010048,
    downloadedBytes: 11010048,
    totalBytes: 11010048,
    cover: "https://picsum.photos/seed/soft-pulse/320/320",
  },
  {
    taskId: "task-20260612-glass-fields",
    type: "download",
    musicId: "yt-arden-glass-fields",
    title: "Arden - Glass Fields",
    status: "finished",
    stage: "completed",
    progress: 100,
    createdAt: "2026-06-12T12:09:00Z",
    updatedAt: "2026-06-12T12:15:00Z",
    filename: "Arden - Glass Fields.mp3",
    filePath: "/sdcard/Music/Arden - Glass Fields.mp3",
    fileSize: 8781824,
    downloadedBytes: 8781824,
    totalBytes: 8781824,
    cover: "https://picsum.photos/seed/glass-fields/320/320",
  },
];

export const mockLogs = [
  "[2026-06-15 09:18:11] service health check ok",
  "[2026-06-15 09:17:42] proxy group Proxy selected HK-01",
  "[2026-06-15 09:16:02] download task completed: Aura - Night Drive.mp3",
  "[2026-06-15 09:15:31] lyrics embedded into MP3",
  "[2026-06-15 09:14:28] yt-dlp cookies enabled",
];

export const initialTimestamp = now.toISOString();
