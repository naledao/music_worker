export type ApiEnvelope<T> = {
  ok: boolean;
  payload: T;
};

export type ErrorPayload = {
  message?: string | null;
};

export type LocalServiceInfo = {
  name: string;
  host: string;
  port: number;
};

export type CookieSnapshot = {
  file?: string | null;
  exists?: boolean;
  size?: number;
  hasYoutube?: boolean;
  enabled?: boolean;
};

export type RuntimeProxySnapshot = {
  ytdlp?: string | null;
  ws?: string | null;
};

export type YtDlpSnapshot = {
  version?: string | null;
  path?: string | null;
  jsRuntime?: string | null;
  remoteComponents?: string | null;
  playerClients?: string[];
  fetchPot?: string | null;
  potTrace?: boolean;
  pluginDir?: string | null;
  potHttp?: string | null;
  potCli?: string | null;
  potScript?: string | null;
};

export type RuntimeSnapshot = {
  cwd?: string | null;
  baseDir?: string | null;
  cookies?: CookieSnapshot | null;
  proxy?: RuntimeProxySnapshot | null;
  ytDlp?: YtDlpSnapshot | null;
  ffmpeg?: string | null;
};

export type TaskStats = {
  total?: number;
  queued?: number;
  running?: number;
  finished?: number;
  failed?: number;
};

export type ProxyInfo = {
  selector?: string | null;
  name?: string | null;
  alive?: boolean | null;
  options?: string[];
};

export type HealthPayload = {
  service: LocalServiceInfo;
  runtime: RuntimeSnapshot;
  tasks: TaskStats;
  proxy: ProxyInfo;
};

export type SearchItem = {
  id: string;
  title: string;
  channel?: string | null;
  duration?: number | null;
  cover?: string | null;
  downloaded?: boolean;
  downloadedFilePath?: string | null;
  downloadedFileSize?: number | null;
  downloadedAt?: string | null;
};

export type SearchPayload = {
  keyword: string;
  results: SearchItem[];
};

export type ChartRegionInfo = {
  id: string;
  label: string;
};

export type ChartSourceInfo = {
  id: string;
  label: string;
  types?: string[];
  periods?: string[];
  regions?: ChartRegionInfo[];
};

export type ChartSourcesPayload = {
  sources?: ChartSourceInfo[];
};

export type ChartItem = {
  rank: number;
  title: string;
  artist: string;
  cover?: string | null;
  album?: string | null;
  durationSec?: number | null;
  deeplink?: string | null;
  searchKeyword: string;
  sourceId?: string | null;
  releaseDate?: string | null;
};

export type ChartPayload = {
  source: string;
  type: string;
  period: string;
  region: string;
  title: string;
  updatedAt?: string | null;
  fromCache?: boolean;
  items?: ChartItem[];
};

export type DownloadTask = {
  taskId: string;
  type?: string;
  musicId: string;
  status: string;
  stage: string;
  progress: number;
  createdAt?: string | null;
  updatedAt?: string | null;
  filename?: string | null;
  filePath?: string | null;
  fileSize?: number | null;
  downloadedBytes?: number;
  totalBytes?: number | null;
  speedBps?: number | null;
  etaSec?: number | null;
  strategy?: string | null;
  lyricsPath?: string | null;
  errorMessage?: string | null;
  errorClass?: string | null;
};

export type TaskListPayload = {
  tasks?: DownloadTask[];
};

export type LogLinesPayload = {
  lines?: string[];
};

export type DownloadedSongItem = {
  musicId: string;
  filePath: string;
  filename?: string | null;
  displayTitle?: string | null;
  fileSize?: number | null;
  durationSec?: number | null;
  downloadedAt?: string | null;
  updatedAt?: string | null;
  lyricsPath?: string | null;
  lyricsExists?: boolean;
  lyricsUpdatedAt?: string | null;
};

export type DownloadedSongsPayload = {
  items?: DownloadedSongItem[];
  total?: number;
  currentPage?: number;
  pageSize?: number;
  totalPages?: number;
};

export type DownloadedLyricsPayload = {
  musicId: string;
  content: string;
  updatedAt?: string | null;
};

export type AppUpdateInfo = {
  versionCode?: number | null;
  versionName?: string | null;
  fileName: string;
  fileSize: number;
  sha256?: string | null;
  updatedAt?: string | null;
  downloadPath: string;
};
