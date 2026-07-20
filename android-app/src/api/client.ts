import type {
  ApiEnvelope,
  AppUpdateInfo,
  ChartPayload,
  ChartSourceInfo,
  ChartSourcesPayload,
  DownloadTask,
  DownloadedLyricsPayload,
  DownloadedSongsPayload,
  ErrorPayload,
  HealthPayload,
  LogLinesPayload,
  ProxyInfo,
  SearchPayload,
  TaskListPayload,
} from "./types";

export const DEFAULT_API_HOST = "127.0.0.1";
export const DEFAULT_API_PORT = 18081;

export type ApiConfig = {
  host: string;
  port: number;
};

export function buildBaseUrl(config: ApiConfig): string {
  const host = config.host.trim() || DEFAULT_API_HOST;
  return `http://${host}:${config.port}`;
}

export class MusicApiClient {
  constructor(private readonly getBaseUrl: () => string) {}

  getHealth(): Promise<HealthPayload> {
    return this.get("/api/health");
  }

  getCurrentProxy(): Promise<ProxyInfo> {
    return this.get("/api/proxy/current");
  }

  async getChartSources(): Promise<ChartSourceInfo[]> {
    const payload = await this.get<ChartSourcesPayload>("/api/charts/sources");
    return payload.sources ?? [];
  }

  getCharts(params: {
    source?: string;
    type?: string;
    period?: string;
    region?: string;
    limit?: number;
    forceRefresh?: boolean;
  }): Promise<ChartPayload> {
    const queryParams: Record<string, string> = {
      source: params.source || "apple_music",
      type: params.type || "songs",
      period: params.period || "daily",
      region: params.region || "us",
      limit: String(Math.min(Math.max(params.limit ?? 50, 1), 100)),
    };
    if (params.forceRefresh) {
      queryParams.force_refresh = "1";
    }
    const query = new URLSearchParams(queryParams);
    return this.get(`/api/charts?${query.toString()}`);
  }

  search(keyword: string, limit = 30): Promise<SearchPayload> {
    return this.post("/api/search", { keyword, limit });
  }

  startDownload(musicId: string): Promise<DownloadTask> {
    return this.post("/api/download", { musicId });
  }

  startLyricsGeneration(musicId: string): Promise<DownloadTask> {
    return this.post("/api/lyrics/generate", { musicId });
  }

  async getTasks(): Promise<DownloadTask[]> {
    const payload = await this.get<TaskListPayload>("/api/tasks");
    return payload.tasks ?? [];
  }

  getTask(taskId: string): Promise<DownloadTask> {
    return this.get(`/api/tasks/${encodePathSegment(taskId)}`);
  }

  getDownloadedSongs(page = 1, pageSize = 10): Promise<DownloadedSongsPayload> {
    return this.get(
      `/api/downloads?page=${Math.max(page, 1)}&page_size=${Math.min(
        Math.max(pageSize, 1),
        100
      )}`
    );
  }

  async getDownloadedSongLyrics(
    musicId: string
  ): Promise<DownloadedLyricsPayload | null> {
    try {
      return await this.get(
        `/api/downloads/${encodePathSegment(musicId)}/lyrics`
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }
      throw error;
    }
  }

  async getLogs(lines = 100): Promise<string[]> {
    const payload = await this.get<LogLinesPayload>(
      `/api/logs?lines=${Math.min(Math.max(lines, 1), 1000)}`
    );
    return payload.lines ?? [];
  }

  getAppUpdate(): Promise<AppUpdateInfo> {
    return this.get("/api/app/update");
  }

  selectProxy(name: string): Promise<ProxyInfo> {
    return this.post("/api/proxy/select", { name });
  }

  taskFileUrl(taskId: string): string {
    return `${this.getBaseUrl()}/api/files/${encodePathSegment(taskId)}`;
  }

  downloadedSongFileUrl(musicId: string): string {
    return `${this.getBaseUrl()}/api/downloads/${encodePathSegment(
      musicId
    )}/file`;
  }

  appUpdateUrl(downloadPath: string): string {
    if (
      downloadPath.startsWith("http://") ||
      downloadPath.startsWith("https://")
    ) {
      return downloadPath;
    }
    return `${this.getBaseUrl()}${downloadPath}`;
  }

  private get<T>(path: string): Promise<T> {
    return this.request<T>(path, { method: "GET" });
  }

  private post<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>(path, {
      method: "POST",
      headers: { "Content-Type": "application/json; charset=utf-8" },
      body: JSON.stringify(body),
    });
  }

  private async request<T>(path: string, init: RequestInit): Promise<T> {
    const url = `${this.getBaseUrl()}${path}`;
    let response: Response;
    try {
      response = await fetch(url, init);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new ApiError(`无法连接本地 API：${message}`);
    }

    const rawBody = await response.text();
    if (!response.ok) {
      throw new ApiError(
        decodeErrorMessage(rawBody) || `HTTP ${response.status}`,
        response.status
      );
    }
    if (!rawBody.trim()) {
      throw new ApiError("API returned empty body", response.status);
    }

    const envelope = JSON.parse(rawBody) as ApiEnvelope<T | ErrorPayload>;
    if (!envelope.ok) {
      const errorPayload = envelope.payload as ErrorPayload;
      throw new ApiError(
        errorPayload.message || "API request failed",
        response.status
      );
    }
    return envelope.payload as T;
  }
}

export class ApiError extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = "ApiError";
  }
}

function decodeErrorMessage(rawBody: string): string {
  if (!rawBody.trim()) {
    return "";
  }
  try {
    const parsed = JSON.parse(rawBody) as Partial<ApiEnvelope<ErrorPayload>>;
    return parsed.payload?.message || rawBody;
  } catch {
    return rawBody;
  }
}

function encodePathSegment(value: string): string {
  return encodeURIComponent(value).replace(/\+/g, "%20");
}
