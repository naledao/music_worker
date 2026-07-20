import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  ActivityIndicator,
  Image,
  Pressable,
  ScrollView,
  Text,
  TextInput,
  View,
} from "react-native";
import {
  ArrowLeft,
  BarChart3,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Download,
  FolderOpen,
  Headphones,
  Home,
  Library,
  ListMusic,
  Mic2,
  Music2,
  Pause,
  Play,
  RefreshCcw,
  Search,
  Server,
  Settings,
  ShieldCheck,
  SkipBack,
  SkipForward,
  Trash2,
  Wifi,
  XCircle,
} from "lucide-react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import "./global.css";
import {
  buildBaseUrl,
  DEFAULT_API_HOST,
  DEFAULT_API_PORT,
  MusicApiClient,
} from "./api/client";
import type {
  AppUpdateInfo,
  ChartItem,
  ChartSourceInfo,
  DownloadTask,
  DownloadedSongItem,
  HealthPayload,
  ProxyInfo,
  SearchItem,
} from "./api/types";
import { cn } from "./lib/cn";
import type {
  MockChartItem,
  MockDownloadedSong,
  MockSearchItem,
  MockTask,
} from "./mock/data";
import {
  addUpdateProgressListener,
  downloadAndInstallUpdate,
  getInstalledAppInfo,
} from "./native/AppUpdateNative";
import {
  addAudioPlayerStatusListener,
  pauseAudio,
  playAudio,
  resumeAudio,
  seekAudioBy,
  type AudioPlayerStatusEvent,
} from "./native/AudioPlayerNative";
import {
  addFileSaveProgressListener,
  clearPersistedDownloadDirectory,
  loadPersistedApiConfig,
  loadPersistedDownloadDirectory,
  pickPersistedDownloadDirectory,
  savePersistedApiConfig,
  saveUrlToPersistedDownloadDirectory,
} from "./native/ApiSettingsNative";
import {
  compactTitle,
  formatBytes,
  formatDuration,
  formatSpeed,
  formatTaskStage,
  formatTaskStatus,
  formatTimestamp,
} from "./utils/format";

type MainRoute = "home" | "search" | "charts" | "library" | "settings";
type Route = MainRoute | "results" | "player" | "tasks";
type PlayerSurface = "cover" | "lyrics";
type TaskTab = "download" | "lyrics";

type IconComponent = React.ComponentType<{
  size?: number;
  color?: string;
  strokeWidth?: number;
  fill?: string;
}>;

type PlaybackTrack = {
  musicId: string;
  title: string;
  subtitle?: string | null;
  durationSec?: number | null;
  cover?: string | null;
  source: "search" | "chart" | "downloaded";
  lyricsContent?: string | null;
};

type PlaybackState = {
  track: PlaybackTrack | null;
  isPlaying: boolean;
  isPreparing: boolean;
  positionSec: number;
  surface: PlayerSurface;
  playbackUrl?: string | null;
  pendingTaskId?: string | null;
  message?: string | null;
  errorMessage?: string | null;
};

type FileSaveProgressState = {
  musicId: string;
  title: string;
  downloadedBytes: number;
  totalBytes: number | null;
  progress: number;
  isSaving: boolean;
};

type SettingsState = {
  host: string;
  port: string;
  directoryLabel: string | null;
  directoryUri: string | null;
  privateStorageBytes: number;
  installedVersionName: string;
  installedVersionCode: number;
  availableUpdate: AppUpdateInfo | null;
  updateProgress: number;
  updateDownloadedBytes: number;
  updateTotalBytes: number | null;
  isCheckingUpdate: boolean;
  isDownloadingUpdate: boolean;
  proxyName: string;
  proxySelector: string;
  proxyAlive: boolean;
  proxyOptions: string[];
  logs: string[];
  message?: string | null;
  errorMessage?: string | null;
};

type DownloadDirectorySelection = {
  uri: string;
  label: string | null;
};

type LocalSaveFeedback = {
  setMessage: (message: string | null) => void;
  setError: (message: string | null) => void;
};

type LocalSaveAudioParams = {
  musicId: string;
  title: string;
  url: string;
  fileName?: string | null;
  fileSize?: number | null;
  directory?: DownloadDirectorySelection | null;
  feedback: LocalSaveFeedback;
};

type PendingLocalSaveRequest = {
  item: MockSearchItem;
  taskId?: string | null;
  directory?: DownloadDirectorySelection | null;
};

type NavItem = {
  route: MainRoute;
  label: string;
  icon: IconComponent;
};

type QuickAction = {
  label: string;
  route: MainRoute;
  icon: IconComponent;
  tone: "emerald" | "sky" | "amber";
};

const MAIN_NAV: NavItem[] = [
  { route: "home", label: "首页", icon: Home },
  { route: "search", label: "搜索", icon: Search },
  { route: "charts", label: "榜单", icon: BarChart3 },
  { route: "library", label: "曲库", icon: Library },
  { route: "settings", label: "设置", icon: Settings },
];

const QUICK_ACTIONS: QuickAction[] = [
  { label: "搜索音乐", route: "search", icon: Search, tone: "emerald" },
  { label: "查看榜单", route: "charts", icon: BarChart3, tone: "sky" },
  { label: "下载曲库", route: "library", icon: Library, tone: "amber" },
];

const SEARCH_PAGE_SIZE = 5;
const LIBRARY_PAGE_SIZE = 4;
const ACTIVE_TASK_STATUSES = new Set(["queued", "running"]);

function App(): React.JSX.Element {
  const [route, setRoute] = useState<Route>("home");
  const [lastMainRoute, setLastMainRoute] = useState<MainRoute>("home");
  const [searchInput, setSearchInput] = useState("Night Drive");
  const [activeKeyword, setActiveKeyword] = useState("");
  const [searchResults, setSearchResults] = useState<MockSearchItem[]>([]);
  const [searchMessage, setSearchMessage] = useState<string | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const [searchPage, setSearchPage] = useState(1);
  const [chartSources, setChartSources] = useState<ChartSourceInfo[]>([]);
  const [chartItems, setChartItems] = useState<MockChartItem[]>([]);
  const [chartTitle, setChartTitle] = useState("Apple Music Top Songs");
  const [chartUpdatedAt, setChartUpdatedAt] = useState<string | null>(null);
  const [chartError, setChartError] = useState<string | null>(null);
  const [selectedRegion, setSelectedRegion] = useState("us");
  const [chartSourcesLoaded, setChartSourcesLoaded] = useState(false);
  const [chartRefreshing, setChartRefreshing] = useState(false);
  const [libraryItems, setLibraryItems] = useState<MockDownloadedSong[]>([]);
  const [libraryTotalItems, setLibraryTotalItems] = useState(0);
  const [libraryTotalPages, setLibraryTotalPages] = useState(1);
  const [libraryPage, setLibraryPage] = useState(1);
  const [libraryMessage, setLibraryMessage] = useState<string | null>(null);
  const [libraryError, setLibraryError] = useState<string | null>(null);
  const [fileSaveProgress, setFileSaveProgress] =
    useState<FileSaveProgressState | null>(null);
  const [pendingLocalSave, setPendingLocalSave] =
    useState<PendingLocalSaveRequest | null>(null);
  const [tasks, setTasks] = useState<MockTask[]>([]);
  const [completedTaskIds, setCompletedTaskIds] = useState<Set<string>>(
    () => new Set()
  );
  const [hasLoadedTasks, setHasLoadedTasks] = useState(false);
  const [currentTaskId, setCurrentTaskId] = useState<string | null>(null);
  const [taskTab, setTaskTab] = useState<TaskTab>("download");
  const [health, setHealth] = useState<HealthPayload>(createEmptyHealth());
  const [apiConfigLoaded, setApiConfigLoaded] = useState(false);
  const [apiReloadKey, setApiReloadKey] = useState(0);
  const [playback, setPlayback] = useState<PlaybackState>({
    track: null,
    isPlaying: false,
    isPreparing: false,
    positionSec: 0,
    surface: "cover",
    playbackUrl: null,
    pendingTaskId: null,
  });
  const [settings, setSettings] = useState<SettingsState>({
    host: "127.0.0.1",
    port: "18081",
    directoryLabel: null,
    directoryUri: null,
    privateStorageBytes: 68 * 1024 * 1024,
    installedVersionName: "1.2.0",
    installedVersionCode: 24,
    availableUpdate: null,
    updateProgress: 0,
    updateDownloadedBytes: 0,
    updateTotalBytes: null,
    isCheckingUpdate: false,
    isDownloadingUpdate: false,
    proxyName: "未加载",
    proxySelector: "Proxy",
    proxyAlive: false,
    proxyOptions: [],
    logs: [],
  });

  const navigate = useCallback((nextRoute: Route) => {
    if (isMainRoute(nextRoute)) {
      setLastMainRoute(nextRoute);
    }
    setRoute(nextRoute);
  }, []);

  const baseUrl = useMemo(
    () =>
      buildBaseUrl({
        host: settings.host,
        port: Number(settings.port) || DEFAULT_API_PORT,
      }),
    [settings.host, settings.port]
  );
  const apiClient = useMemo(() => new MusicApiClient(() => baseUrl), [baseUrl]);

  const taskStats = useMemo(() => buildTaskStats(tasks), [tasks]);
  const activeTaskCount = taskStats.queued + taskStats.running;
  const homeHealth = useMemo<HealthPayload>(
    () => ({
      ...health,
      service: {
        ...health.service,
        host: settings.host.trim() || DEFAULT_API_HOST,
        port: Number(settings.port) || DEFAULT_API_PORT,
      },
      tasks: taskStats,
      proxy: {
        selector: settings.proxySelector || health.proxy.selector,
        name: settings.proxyName || health.proxy.name,
        alive: settings.proxyAlive,
        options: settings.proxyOptions.length
          ? settings.proxyOptions
          : health.proxy.options || [],
      },
    }),
    [health, settings, taskStats]
  );
  const selectedChartSource = chartSources[0] || null;
  const selectedChartRegions = selectedChartSource?.regions || [];
  const selectedRegionLabel = useMemo(
    () =>
      selectedChartRegions.find((region) => region.id === selectedRegion)
        ?.label || selectedRegion.toUpperCase(),
    [selectedChartRegions, selectedRegion]
  );
  const searchTotalPages = Math.max(
    1,
    Math.ceil(searchResults.length / SEARCH_PAGE_SIZE)
  );
  const pagedSearchResults = useMemo(
    () =>
      searchResults.slice(
        (searchPage - 1) * SEARCH_PAGE_SIZE,
        searchPage * SEARCH_PAGE_SIZE
      ),
    [searchPage, searchResults]
  );
  const pagedLibraryItems = libraryItems;
  const currentTask = useMemo(
    () =>
      tasks.find((task) => task.taskId === currentTaskId) ||
      tasks.find(
        (task) =>
          task.type === "download" && ACTIVE_TASK_STATUSES.has(task.status)
      ) ||
      null,
    [currentTaskId, tasks]
  );
  const activeLyricsTasks = useMemo(() => {
    const entries = tasks
      .filter(
        (task) =>
          task.type === "lyrics" && ACTIVE_TASK_STATUSES.has(task.status)
      )
      .map((task) => [task.musicId, task] as const);
    return new Map(entries);
  }, [tasks]);
  const activeDownloadTasks = useMemo(() => {
    const entries = tasks
      .filter(
        (task) =>
          task.type !== "lyrics" && ACTIVE_TASK_STATUSES.has(task.status)
      )
      .map((task) => [task.musicId, task] as const);
    return new Map(entries);
  }, [tasks]);
  const hasActiveTasks = tasks.some((task) =>
    ACTIVE_TASK_STATUSES.has(task.status)
  );

  useEffect(() => {
    let cancelled = false;

    loadPersistedApiConfig()
      .then((config) => {
        if (cancelled) {
          return;
        }
        if (config) {
          setSettings((previous) => ({
            ...previous,
            host: config.host,
            port: config.port,
          }));
        }
      })
      .catch(() => {
        // Defaults are usable if persisted settings cannot be read.
      })
      .finally(() => {
        if (!cancelled) {
          setApiConfigLoaded(true);
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;

    loadPersistedDownloadDirectory()
      .then((directory) => {
        if (!directory || cancelled) {
          return;
        }
        setSettings((previous) => ({
          ...previous,
          directoryLabel: directory.label,
          directoryUri: directory.uri,
        }));
      })
      .catch(() => {
        // Directory selection is optional.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!apiConfigLoaded) {
      return;
    }

    let cancelled = false;

    const appendStartupLog = (message: string, errorMessage?: string) => {
      if (cancelled) {
        return;
      }
      setSettings((previous) => ({
        ...previous,
        errorMessage: errorMessage ?? previous.errorMessage,
        logs: [`[${formatNow()}] ${message}`, ...previous.logs],
      }));
    };

    setChartSourcesLoaded(false);

    async function loadHealthSnapshot() {
      try {
        const healthPayload = await apiClient.getHealth();
        if (cancelled) {
          return;
        }
        setHealth(healthPayload);
        setSettings((previous) => ({ ...previous, errorMessage: null }));
        applyProxyInfo(healthPayload.proxy, setSettings);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        appendStartupLog(
          `health refresh failed: ${message}`,
          `刷新服务状态失败：${message}`
        );
      }
    }

    async function loadChartSources() {
      try {
        const sourcePayload = await apiClient.getChartSources();
        if (cancelled) {
          return;
        }
        setChartSources(sourcePayload);
        const firstRegion = sourcePayload[0]?.regions?.[0]?.id;
        if (firstRegion) {
          setSelectedRegion((currentRegion) =>
            sourcePayload[0]?.regions?.some(
              (region) => region.id === currentRegion
            )
              ? currentRegion
              : firstRegion
          );
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setChartError(message);
        appendStartupLog(`chart sources load failed: ${message}`);
      } finally {
        if (!cancelled) {
          setChartSourcesLoaded(true);
        }
      }
    }

    async function loadLibraryAndTasks() {
      const [downloadsResult, taskResult] = await Promise.allSettled([
        apiClient.getDownloadedSongs(1, LIBRARY_PAGE_SIZE),
        apiClient.getTasks(),
      ]);

      if (cancelled) {
        return;
      }

      let downloadedItems: MockDownloadedSong[] = [];
      if (downloadsResult.status === "fulfilled") {
        downloadedItems = (downloadsResult.value.items || []).map((item) =>
          downloadedSongFromBackend(item, [])
        );
        setLibraryItems(downloadedItems);
        setLibraryTotalItems(
          downloadsResult.value.total ?? downloadedItems.length
        );
        setLibraryPage(downloadsResult.value.currentPage || 1);
        setLibraryTotalPages(
          Math.max(1, downloadsResult.value.totalPages || 1)
        );
        setLibraryError(null);
      } else {
        const message =
          downloadsResult.reason instanceof Error
            ? downloadsResult.reason.message
            : String(downloadsResult.reason);
        setLibraryError(message);
        appendStartupLog(`library load failed: ${message}`);
      }

      if (taskResult.status === "fulfilled") {
        const nextTasks = taskResult.value.map((task) =>
          taskFromBackend(task, [], downloadedItems)
        );
        setTasks(nextTasks);
        setCompletedTaskIds(
          new Set(
            nextTasks
              .filter((task) => task.status === "finished")
              .map((task) => task.taskId)
          )
        );
      } else {
        const message =
          taskResult.reason instanceof Error
            ? taskResult.reason.message
            : String(taskResult.reason);
        appendStartupLog(`task load failed: ${message}`);
      }
      setHasLoadedTasks(true);
    }

    async function loadLogLines() {
      try {
        const logsPayload = await apiClient.getLogs(100);
        if (cancelled) {
          return;
        }
        setSettings((previous) => ({
          ...previous,
          logs: logsPayload,
        }));
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        appendStartupLog(`log refresh failed: ${message}`);
      }
    }

    void loadHealthSnapshot();
    void loadLibraryAndTasks();
    void loadChartSources();
    void loadLogLines();

    return () => {
      cancelled = true;
    };
    // The reload key is advanced only after config load/save, avoiding requests while the user edits host/port.
  }, [apiConfigLoaded, apiReloadKey]);

  useEffect(() => {
    getInstalledAppInfo()
      .then((info) => {
        setSettings((previous) => ({
          ...previous,
          installedVersionName: info.versionName,
          installedVersionCode: info.versionCode,
        }));
      })
      .catch(() => {
        // The screen can still render in JS-only preview environments.
      });
  }, []);

  const refreshHealth = useCallback(async () => {
    try {
      const payload = await apiClient.getHealth();
      setHealth(payload);
      applyProxyInfo(payload.proxy, setSettings);
      return payload;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setSettings((previous) => ({
        ...previous,
        errorMessage: `刷新服务状态失败：${message}`,
        logs: [
          `[${formatNow()}] health refresh failed: ${message}`,
          ...previous.logs,
        ],
      }));
      throw error;
    }
  }, [apiClient]);

  const refreshLogs = useCallback(async () => {
    try {
      const lines = await apiClient.getLogs(100);
      setSettings((previous) => ({
        ...previous,
        logs: lines,
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setSettings((previous) => ({
        ...previous,
        logs: [
          `[${formatNow()}] log refresh failed: ${message}`,
          ...previous.logs,
        ],
      }));
    }
  }, [apiClient]);

  const refreshLibrary = useCallback(
    async (page = libraryPage, options?: { silent?: boolean }) => {
      try {
        const payload = await apiClient.getDownloadedSongs(
          page,
          LIBRARY_PAGE_SIZE
        );
        const nextItems = (payload.items || []).map((item) =>
          downloadedSongFromBackend(item, searchResults)
        );
        setLibraryItems(nextItems);
        setLibraryTotalItems(payload.total ?? nextItems.length);
        setLibraryPage(payload.currentPage || page);
        setLibraryTotalPages(Math.max(1, payload.totalPages || 1));
        setLibraryError(null);
        if (!options?.silent) {
          setLibraryMessage("曲库已刷新");
        }
        return nextItems;
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setLibraryError(message);
        if (!options?.silent) {
          setLibraryMessage(null);
        }
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] library refresh failed: ${message}`,
            ...previous.logs,
          ],
        }));
        return [];
      }
    },
    [apiClient, libraryPage, searchResults]
  );

  const refreshTasks = useCallback(
    async (options?: { markExistingFinished?: boolean }) => {
      try {
        const payload = await apiClient.getTasks();
        const nextTasks = payload.map((task) =>
          taskFromBackend(task, searchResults, libraryItems)
        );
        setTasks(nextTasks);
        if (options?.markExistingFinished) {
          setCompletedTaskIds(
            new Set(
              nextTasks
                .filter((task) => task.status === "finished")
                .map((task) => task.taskId)
            )
          );
          setHasLoadedTasks(true);
        }
        return nextTasks;
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] task refresh failed: ${message}`,
            ...previous.logs,
          ],
        }));
        if (options?.markExistingFinished) {
          setHasLoadedTasks(true);
        }
        return [];
      }
    },
    [apiClient, libraryItems, searchResults]
  );

  const refreshCharts = useCallback(
    async (forceRefresh = false) => {
      setChartRefreshing(true);
      try {
        const payload = await apiClient.getCharts({
          source: selectedChartSource?.id || "apple_music",
          type: selectedChartSource?.types?.[0] || "songs",
          period: selectedChartSource?.periods?.[0] || "daily",
          region: selectedRegion,
          limit: 50,
          forceRefresh,
        });
        setChartItems(
          (payload.items || []).map((item, index) =>
            chartItemFromBackend(item, payload.source, index)
          )
        );
        setChartTitle(payload.title || "Apple Music Top Songs");
        setChartUpdatedAt(payload.updatedAt || null);
        setChartError(null);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setChartError(message);
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] chart refresh failed: ${message}`,
            ...previous.logs,
          ],
        }));
      } finally {
        setChartRefreshing(false);
      }
    },
    [apiClient, selectedChartSource, selectedRegion]
  );

  const handleSaveApiConfig = useCallback(async () => {
    const host = settings.host.trim() || DEFAULT_API_HOST;
    const parsedPort = Number(settings.port);
    const port =
      Number.isInteger(parsedPort) && parsedPort >= 1 && parsedPort <= 65535
        ? String(parsedPort)
        : String(DEFAULT_API_PORT);

    try {
      const savedConfig = await savePersistedApiConfig({ host, port });
      setSettings((previous) => ({
        ...previous,
        host: savedConfig.host,
        port: savedConfig.port,
        message: "配置已保存，正在刷新后端状态",
        errorMessage: null,
        logs: [
          `[${formatNow()}] api config saved ${savedConfig.host}:${
            savedConfig.port
          }`,
          ...previous.logs,
        ],
      }));
      setApiReloadKey((key) => key + 1);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setSettings((previous) => ({
        ...previous,
        errorMessage: `配置保存失败：${message}`,
        logs: [
          `[${formatNow()}] api config save failed: ${message}`,
          ...previous.logs,
        ],
      }));
    }
  }, [settings.host, settings.port]);

  const startNativePlayback = useCallback(
    async (track: PlaybackTrack, playbackUrl: string, message: string) => {
      setPlayback((previous) => ({
        ...previous,
        track,
        isPlaying: false,
        isPreparing: true,
        positionSec: 0,
        playbackUrl,
        pendingTaskId: null,
        surface:
          previous.track?.musicId === track.musicId
            ? previous.surface
            : "cover",
        message: "正在缓冲音频",
        errorMessage: null,
      }));

      try {
        await playAudio({
          url: playbackUrl,
          title: track.title,
          durationSec: track.durationSec,
        });
        setPlayback((previous) => ({
          ...previous,
          track,
          playbackUrl,
          isPreparing: true,
          pendingTaskId: null,
          message,
          errorMessage: null,
        }));
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        setPlayback((previous) => ({
          ...previous,
          track,
          isPlaying: false,
          isPreparing: false,
          playbackUrl: null,
          pendingTaskId: null,
          message: null,
          errorMessage: `播放失败：${errorMessage}`,
        }));
      }
    },
    []
  );

  useEffect(() => {
    if (!apiConfigLoaded || !hasActiveTasks) {
      return;
    }

    const timer = setInterval(() => {
      void refreshTasks();
    }, 1500);

    return () => clearInterval(timer);
  }, [apiConfigLoaded, hasActiveTasks, refreshTasks]);

  useEffect(() => {
    if (!hasLoadedTasks) {
      return;
    }

    const newlyCompleted = tasks.filter(
      (task) => task.status === "finished" && !completedTaskIds.has(task.taskId)
    );
    if (newlyCompleted.length === 0) {
      return;
    }

    const downloads = newlyCompleted.filter((task) => task.type !== "lyrics");
    const lyricsTasks = newlyCompleted.filter((task) => task.type === "lyrics");

    if (downloads.length > 0) {
      setSearchResults((previousResults) =>
        previousResults.map((item) => {
          const task = downloads.find(
            (download) => download.musicId === item.id
          );
          if (!task) {
            return item;
          }
          return {
            ...item,
            downloaded: true,
            downloadedFilePath: task.filePath,
            downloadedFileSize: task.fileSize,
            downloadedAt: task.updatedAt,
          };
        })
      );
      setLibraryMessage(`${downloads[0]?.title || "歌曲"} 已加入曲库`);
      void refreshLibrary(1, { silent: true });

      const playbackTrack = playback.track;
      const readyPlaybackTask =
        playbackTrack && playback.isPreparing
          ? downloads.find(
              (task) =>
                task.taskId === playback.pendingTaskId ||
                task.musicId === playbackTrack.musicId
            )
          : null;
      if (playbackTrack && readyPlaybackTask) {
        void startNativePlayback(
          playbackTrack,
          apiClient.taskFileUrl(readyPlaybackTask.taskId),
          "服务端音频已准备完成，正在在线播放"
        );
      }
    }

    if (lyricsTasks.length > 0) {
      setLibraryMessage(`${lyricsTasks[0]?.title || "歌曲"} 的 LRC 已生成`);
      void refreshLibrary(libraryPage, { silent: true });
    }

    void refreshLogs();

    setCompletedTaskIds((previousIds) => {
      const nextIds = new Set(previousIds);
      newlyCompleted.forEach((task) => nextIds.add(task.taskId));
      return nextIds;
    });
  }, [
    completedTaskIds,
    apiClient,
    hasLoadedTasks,
    libraryPage,
    playback,
    refreshLibrary,
    refreshLogs,
    startNativePlayback,
    tasks,
  ]);

  useEffect(() => {
    if (!playback.pendingTaskId || !playback.isPreparing) {
      return;
    }

    const failedTask = tasks.find(
      (task) =>
        task.taskId === playback.pendingTaskId && task.status === "failed"
    );
    if (!failedTask) {
      return;
    }

    setPlayback((previous) => ({
      ...previous,
      isPlaying: false,
      isPreparing: false,
      playbackUrl: null,
      pendingTaskId: null,
      message: null,
      errorMessage: failedTask.errorMessage || "服务端准备音频失败",
    }));
  }, [playback.isPreparing, playback.pendingTaskId, tasks]);

  useEffect(
    () =>
      addAudioPlayerStatusListener((event) => {
        setPlayback((previousState) =>
          applyAudioStatusToPlayback(previousState, event)
        );
      }),
    []
  );

  useEffect(
    () =>
      addFileSaveProgressListener((event) => {
        const musicId = (event.musicId || "").trim();
        if (!musicId) {
          return;
        }
        setFileSaveProgress((previous) => {
          if (!previous || previous.musicId !== musicId) {
            return previous;
          }
          const progressPercent = Math.round((event.progress || 0) * 100);
          return {
            ...previous,
            downloadedBytes: event.downloadedBytes || 0,
            totalBytes: event.totalBytes ?? previous.totalBytes,
            progress: Math.max(0, Math.min(100, progressPercent)),
          };
        });
      }),
    []
  );

  const hydrateSearchResult = useCallback(
    (item: SearchItem): MockSearchItem => {
      const downloadedSong = libraryItems.find(
        (song) => song.musicId === item.id
      );
      if (!downloadedSong) {
        return { ...item, downloaded: Boolean(item.downloaded) };
      }
      return {
        ...item,
        downloaded: true,
        downloadedFilePath: downloadedSong.filePath,
        downloadedFileSize: downloadedSong.fileSize,
        downloadedAt: downloadedSong.downloadedAt,
      };
    },
    [libraryItems]
  );

  const performSearch = useCallback(
    async (keywordOverride?: string) => {
      const keyword = (keywordOverride ?? searchInput).trim();
      if (!keyword) {
        setSearchError("请输入关键词");
        return;
      }

      setIsSearching(true);
      setSearchInput(keyword);
      setActiveKeyword(keyword);
      setSearchMessage(null);
      setSearchError(null);
      navigate("results");

      try {
        const payload = await apiClient.search(keyword, 30);
        const results = (payload.results || []).map(hydrateSearchResult);
        setSearchResults(results);
        setSearchPage(1);
        setSearchMessage(null);
        setSearchError(null);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setSearchResults([]);
        setSearchPage(1);
        setSearchMessage(null);
        setSearchError(`搜索失败：${message}`);
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] search failed keyword=${keyword}: ${message}`,
            ...previous.logs,
          ],
        }));
      } finally {
        setIsSearching(false);
      }
    },
    [apiClient, hydrateSearchResult, navigate, searchInput]
  );

  const playTrack = useCallback(
    async (track: PlaybackTrack) => {
      navigate("player");
      setPlayback({
        track,
        isPlaying: false,
        isPreparing: true,
        positionSec: 0,
        surface: "cover",
        playbackUrl: null,
        pendingTaskId: null,
        message:
          track.source === "downloaded"
            ? "正在打开已下载音频"
            : "正在通知服务端准备音频",
        errorMessage: null,
      });

      if (track.source === "downloaded") {
        await startNativePlayback(
          track,
          apiClient.downloadedSongFileUrl(track.musicId),
          "正在在线播放已下载音频"
        );
        return;
      }

      try {
        const task = taskFromBackend(
          await apiClient.startDownload(track.musicId),
          searchResults,
          libraryItems,
          track
        );
        setTasks((previousTasks) => upsertTask(previousTasks, task));
        setCurrentTaskId(task.taskId);

        if (task.status === "finished") {
          await startNativePlayback(
            track,
            apiClient.taskFileUrl(task.taskId),
            "服务端音频已准备完成，正在在线播放"
          );
          void refreshLibrary(1, { silent: true });
          return;
        }

        setPlayback((previous) => ({
          ...previous,
          track,
          isPlaying: false,
          isPreparing: true,
          playbackUrl: null,
          pendingTaskId: task.taskId,
          message: "服务端正在准备音频",
          errorMessage: null,
        }));
        void refreshTasks();
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setPlayback((previous) => ({
          ...previous,
          track,
          isPlaying: false,
          isPreparing: false,
          playbackUrl: null,
          pendingTaskId: null,
          message: null,
          errorMessage: `准备播放失败：${message}`,
        }));
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] playback prepare failed musicId=${
              track.musicId
            }: ${message}`,
            ...previous.logs,
          ],
        }));
      }
    },
    [
      apiClient,
      libraryItems,
      navigate,
      refreshLibrary,
      refreshTasks,
      searchResults,
      startNativePlayback,
    ]
  );

  const playChartItem = useCallback(
    async (item: MockChartItem) => {
      navigate("player");
      const chartTrack: PlaybackTrack = {
        musicId: item.musicId,
        title: `${item.artist} - ${item.title}`,
        subtitle: item.album || item.artist,
        durationSec: item.durationSec,
        cover: item.cover,
        source: "chart",
      };
      setPlayback({
        track: chartTrack,
        isPlaying: false,
        isPreparing: true,
        positionSec: 0,
        surface: "cover",
        playbackUrl: null,
        pendingTaskId: null,
        message: "正在搜索榜单歌曲音频",
        errorMessage: null,
      });

      try {
        const payload = await apiClient.search(item.searchKeyword, 1);
        const firstResult = payload.results?.[0];
        if (!firstResult) {
          throw new Error("后端未找到可播放音频");
        }
        const result = hydrateSearchResult(firstResult);
        setSearchInput(item.searchKeyword);
        setActiveKeyword(item.searchKeyword);
        setSearchResults([result]);
        setSearchPage(1);
        setSearchError(null);
        await playTrack({
          musicId: result.id,
          title: result.title,
          subtitle: result.channel,
          durationSec: result.duration,
          cover: result.cover || item.cover,
          source: "search",
          lyricsContent: result.lyrics,
        });
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setPlayback((previous) => ({
          ...previous,
          track: chartTrack,
          isPlaying: false,
          isPreparing: false,
          playbackUrl: null,
          pendingTaskId: null,
          message: null,
          errorMessage: `榜单播放失败：${message}`,
        }));
      }
    },
    [apiClient, hydrateSearchResult, navigate, playTrack]
  );

  const startDownload = useCallback(
    async (track: PlaybackTrack) => {
      if (libraryItems.some((item) => item.musicId === track.musicId)) {
        setLibraryMessage(`${track.title} 已在曲库中`);
        navigate("library");
        return;
      }

      try {
        setSearchMessage(null);
        const task = taskFromBackend(
          await apiClient.startDownload(track.musicId),
          searchResults,
          libraryItems,
          track
        );
        setTasks((previousTasks) => upsertTask(previousTasks, task));
        setCurrentTaskId(task.taskId);
        setTaskTab("download");
        navigate("tasks");
        void refreshTasks();
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setSearchError(`创建下载任务失败：${message}`);
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] download task create failed musicId=${
              track.musicId
            }: ${message}`,
            ...previous.logs,
          ],
        }));
      }
    },
    [apiClient, libraryItems, navigate, refreshTasks, searchResults]
  );

  const startLyricsGeneration = useCallback(
    async (song: MockDownloadedSong) => {
      const existingTask = tasks.find(
        (task) =>
          task.type === "lyrics" &&
          task.musicId === song.musicId &&
          ACTIVE_TASK_STATUSES.has(task.status)
      );
      if (existingTask) {
        setTaskTab("lyrics");
        navigate("tasks");
        return;
      }

      try {
        const task = taskFromBackend(
          await apiClient.startLyricsGeneration(song.musicId),
          searchResults,
          libraryItems,
          {
            musicId: song.musicId,
            title: compactTitle(
              song.displayTitle || song.filename,
              song.musicId
            ),
            subtitle: song.filename,
            durationSec: song.durationSec,
            cover: song.cover,
            source: "downloaded",
          }
        );
        setTasks((previousTasks) => upsertTask(previousTasks, task));
        setCurrentTaskId(task.taskId);
        setTaskTab("lyrics");
        navigate("tasks");
        void refreshTasks();
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setLibraryError(message);
        setLibraryMessage(null);
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] lyrics task create failed musicId=${
              song.musicId
            }: ${message}`,
            ...previous.logs,
          ],
        }));
      }
    },
    [apiClient, libraryItems, navigate, refreshTasks, searchResults, tasks]
  );

  const handleChartSearch = useCallback(
    (item: MockChartItem) => {
      void performSearch(item.searchKeyword);
    },
    [performSearch]
  );

  const handleRefreshCharts = useCallback(() => {
    void refreshCharts(true);
  }, [refreshCharts]);

  useEffect(() => {
    if (!apiConfigLoaded || !chartSourcesLoaded) {
      return;
    }
    void refreshCharts(false);
  }, [apiConfigLoaded, chartSourcesLoaded, refreshCharts]);

  const handleCheckAppUpdate = useCallback(async () => {
    setSettings((previous) => ({
      ...previous,
      isCheckingUpdate: true,
      message: null,
      errorMessage: null,
    }));

    try {
      const updateInfo = await apiClient.getAppUpdate();
      setSettings((previous) => ({
        ...previous,
        availableUpdate: updateInfo,
        isCheckingUpdate: false,
        updateProgress: 0,
        updateDownloadedBytes: 0,
        updateTotalBytes: updateInfo.fileSize || null,
        message:
          updateInfo.versionCode != null &&
          updateInfo.versionCode > previous.installedVersionCode
            ? `发现新版本：${updateInfo.versionName || "unknown"} (${
                updateInfo.versionCode
              })`
            : `当前版本：${previous.installedVersionName} (${previous.installedVersionCode})`,
        logs: [`[${formatNow()}] app update manifest loaded`, ...previous.logs],
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setSettings((previous) => ({
        ...previous,
        isCheckingUpdate: false,
        errorMessage: `检查更新失败：${message}`,
        logs: [
          `[${formatNow()}] app update check failed: ${message}`,
          ...previous.logs,
        ],
      }));
    }
  }, [apiClient]);

  const handleDownloadAndInstallAppUpdate = useCallback(async () => {
    const updateInfo = settings.availableUpdate;
    if (!updateInfo) {
      setSettings((previous) => ({
        ...previous,
        errorMessage: "请先检查更新",
      }));
      return;
    }

    setSettings((previous) => ({
      ...previous,
      isDownloadingUpdate: true,
      updateProgress: 0,
      updateDownloadedBytes: 0,
      updateTotalBytes: updateInfo.fileSize || null,
      message: null,
      errorMessage: null,
    }));

    const removeProgressListener = addUpdateProgressListener((event) => {
      setSettings((previous) => ({
        ...previous,
        updateDownloadedBytes: event.downloadedBytes,
        updateTotalBytes: event.totalBytes ?? previous.updateTotalBytes,
        updateProgress: Math.round((event.progress || 0) * 100),
      }));
    });

    try {
      const result = await downloadAndInstallUpdate({
        downloadUrl: apiClient.appUpdateUrl(updateInfo.downloadPath),
        fileName: updateInfo.fileName,
        expectedSha256: updateInfo.sha256,
      });
      setSettings((previous) => ({
        ...previous,
        isDownloadingUpdate: false,
        updateProgress: 100,
        updateDownloadedBytes:
          updateInfo.fileSize || previous.updateDownloadedBytes,
        updateTotalBytes: updateInfo.fileSize || previous.updateTotalBytes,
        message: result.message,
        logs: [
          `[${formatNow()}] app update ${result.status}`,
          ...previous.logs,
        ],
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setSettings((previous) => ({
        ...previous,
        isDownloadingUpdate: false,
        errorMessage: `下载更新失败：${message}`,
        logs: [
          `[${formatNow()}] app update download failed: ${message}`,
          ...previous.logs,
        ],
      }));
    } finally {
      removeProgressListener();
    }
  }, [apiClient, settings.availableUpdate]);

  const handleBack = useCallback(() => {
    if (route === "results") {
      navigate("search");
      return;
    }
    if (route === "tasks" || route === "player") {
      navigate(lastMainRoute);
      return;
    }
    navigate("home");
  }, [lastMainRoute, navigate, route]);

  const handleSelectProxy = useCallback(
    async (name: string) => {
      setSettings((previous) => ({
        ...previous,
        message: null,
        errorMessage: null,
      }));
      try {
        const proxy = await apiClient.selectProxy(name);
        applyProxyInfo(proxy, setSettings);
        setSettings((previous) => ({
          ...previous,
          message: `代理节点已切换到 ${proxy.name || name}`,
          logs: [
            `[${formatNow()}] proxy selected ${proxy.name || name}`,
            ...previous.logs,
          ],
        }));
        void refreshHealth();
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setSettings((previous) => ({
          ...previous,
          errorMessage: `代理切换失败：${message}`,
          logs: [
            `[${formatNow()}] proxy select failed name=${name}: ${message}`,
            ...previous.logs,
          ],
        }));
      }
    },
    [apiClient, refreshHealth]
  );

  const handlePickDownloadDirectory = useCallback(async () => {
    try {
      const directory = await pickPersistedDownloadDirectory();
      setSettings((previous) => ({
        ...previous,
        directoryLabel: directory.label,
        directoryUri: directory.uri,
        message: directory.uri
          ? `下载目录已设置为：${directory.label || directory.uri}`
          : "未选择下载目录",
        errorMessage: null,
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (message.includes("未选择下载目录")) {
        return;
      }
      setSettings((previous) => ({
        ...previous,
        errorMessage: `选择下载目录失败：${message}`,
      }));
    }
  }, []);

  const handleClearDownloadDirectory = useCallback(async () => {
    try {
      await clearPersistedDownloadDirectory();
      setSettings((previous) => ({
        ...previous,
        directoryLabel: null,
        directoryUri: null,
        message: "已清除下载目录",
        errorMessage: null,
      }));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setSettings((previous) => ({
        ...previous,
        errorMessage: `清除下载目录失败：${message}`,
      }));
    }
  }, []);

  const ensureDownloadDirectory = useCallback(
    async (
      presetDirectory?: DownloadDirectorySelection | null
    ): Promise<DownloadDirectorySelection | null> => {
      if (presetDirectory?.uri) {
        return presetDirectory;
      }

      if (settings.directoryUri) {
        return {
          uri: settings.directoryUri,
          label: settings.directoryLabel,
        };
      }

      const directory = await pickPersistedDownloadDirectory();
      setSettings((previous) => ({
        ...previous,
        directoryLabel: directory.label,
        directoryUri: directory.uri,
        message: directory.uri
          ? `下载目录已设置为：${directory.label || directory.uri}`
          : "未选择下载目录",
        errorMessage: null,
      }));
      if (!directory.uri) {
        return null;
      }
      return {
        uri: directory.uri,
        label: directory.label,
      };
    },
    [settings.directoryLabel, settings.directoryUri]
  );

  const saveAudioUrlToLocalDirectory = useCallback(
    async ({
      musicId,
      title,
      url,
      fileName,
      fileSize,
      directory,
      feedback,
    }: LocalSaveAudioParams): Promise<boolean> => {
      if (fileSaveProgress?.isSaving) {
        feedback.setMessage(`${fileSaveProgress.title} 正在保存`);
        feedback.setError(null);
        return false;
      }

      try {
        const selectedDirectory = await ensureDownloadDirectory(directory);
        if (!selectedDirectory) {
          feedback.setMessage(null);
          feedback.setError("请先选择下载目录");
          return false;
        }

        feedback.setError(null);
        feedback.setMessage(`正在保存 ${title}`);
        setFileSaveProgress({
          musicId,
          title,
          downloadedBytes: 0,
          totalBytes: fileSize ?? null,
          progress: 0,
          isSaving: true,
        });
        const savedFile = await saveUrlToPersistedDownloadDirectory({
          url,
          fileName: fileName || `${title}.mp3`,
          musicId,
        });
        setFileSaveProgress({
          musicId,
          title,
          downloadedBytes: savedFile.fileSize,
          totalBytes: savedFile.fileSize,
          progress: 100,
          isSaving: false,
        });
        feedback.setMessage(
          `${title} 已保存到 ${
            selectedDirectory.label || "下载目录"
          }：${savedFile.fileName}`
        );
        feedback.setError(null);
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] song saved musicId=${musicId} file=${
              savedFile.fileName
            } size=${formatBytes(savedFile.fileSize)}`,
            ...previous.logs,
          ],
        }));
        return true;
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        if (message.includes("未选择下载目录")) {
          feedback.setMessage(null);
          feedback.setError(null);
          return false;
        }
        setFileSaveProgress((previous) =>
          previous?.musicId === musicId ? null : previous
        );
        feedback.setMessage(null);
        feedback.setError(message);
        return false;
      }
    },
    [ensureDownloadDirectory, fileSaveProgress]
  );

  const handleSaveDownloadedSong = useCallback(
    async (song: MockDownloadedSong) => {
      const title = compactTitle(
        song.displayTitle || song.filename,
        song.musicId
      );
      await saveAudioUrlToLocalDirectory({
        musicId: song.musicId,
        title,
        url: apiClient.downloadedSongFileUrl(song.musicId),
        fileName: song.filename || `${title}.mp3`,
        fileSize: song.fileSize,
        feedback: {
          setMessage: setLibraryMessage,
          setError: setLibraryError,
        },
      });
    },
    [apiClient, saveAudioUrlToLocalDirectory]
  );

  const handleSaveSearchItemToLocal = useCallback(
    async (item: MockSearchItem) => {
      const title = compactTitle(item.title, item.id);
      const existingPendingSave = pendingLocalSave?.item.id === item.id;
      if (existingPendingSave) {
        setSearchError(null);
        setSearchMessage(`${title} 下载完成后会自动保存到本地`);
        return;
      }
      if (fileSaveProgress?.isSaving) {
        setSearchError(null);
        setSearchMessage(`${fileSaveProgress.title} 正在保存`);
        return;
      }

      let directory: DownloadDirectorySelection | null = null;
      try {
        directory = await ensureDownloadDirectory();
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        if (message.includes("未选择下载目录")) {
          setSearchMessage(null);
          return;
        }
        setSearchMessage(null);
        setSearchError(`选择下载目录失败：${message}`);
        return;
      }
      if (!directory) {
        setSearchMessage(null);
        setSearchError("请先选择下载目录");
        return;
      }

      const downloadedSong = libraryItems.find(
        (song) => song.musicId === item.id
      );
      const fallbackFileName =
        downloadedSong?.filename || buildSearchItemFileName(item, title);
      const fallbackFileSize =
        downloadedSong?.fileSize ?? item.downloadedFileSize ?? null;

      if (item.downloaded || downloadedSong) {
        await saveAudioUrlToLocalDirectory({
          musicId: item.id,
          title,
          url: apiClient.downloadedSongFileUrl(item.id),
          fileName: fallbackFileName,
          fileSize: fallbackFileSize,
          directory,
          feedback: {
            setMessage: setSearchMessage,
            setError: setSearchError,
          },
        });
        return;
      }

      const activeTask = activeDownloadTasks.get(item.id);
      if (activeTask) {
        setPendingLocalSave({
          item,
          taskId: activeTask.taskId,
          directory,
        });
        setSearchError(null);
        setSearchMessage(`${title} 下载完成后会自动保存到本地`);
        return;
      }

      try {
        setSearchError(null);
        setSearchMessage(`正在准备 ${title}，完成后保存到本地`);
        const track: PlaybackTrack = {
          musicId: item.id,
          title: item.title,
          subtitle: item.channel,
          durationSec: item.duration,
          cover: item.cover,
          source: "search",
          lyricsContent: item.lyrics,
        };
        const task = taskFromBackend(
          await apiClient.startDownload(item.id),
          searchResults,
          libraryItems,
          track
        );
        setTasks((previousTasks) => upsertTask(previousTasks, task));
        setCurrentTaskId(task.taskId);
        setTaskTab("download");

        if (task.status === "finished") {
          setSearchResults((previousResults) =>
            previousResults.map((result) =>
              result.id === item.id
                ? {
                    ...result,
                    downloaded: true,
                    downloadedFilePath: task.filePath,
                    downloadedFileSize: task.fileSize,
                    downloadedAt: task.updatedAt,
                  }
                : result
            )
          );
          void refreshLibrary(1, { silent: true });
          await saveAudioUrlToLocalDirectory({
            musicId: item.id,
            title,
            url: apiClient.taskFileUrl(task.taskId),
            fileName: task.filename || fallbackFileName,
            fileSize: task.fileSize ?? fallbackFileSize,
            directory,
            feedback: {
              setMessage: setSearchMessage,
              setError: setSearchError,
            },
          });
          return;
        }

        setPendingLocalSave({
          item,
          taskId: task.taskId,
          directory,
        });
        void refreshTasks();
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setPendingLocalSave((previous) =>
          previous?.item.id === item.id ? null : previous
        );
        setSearchMessage(null);
        setSearchError(`保存到本地失败：${message}`);
        setSettings((previous) => ({
          ...previous,
          logs: [
            `[${formatNow()}] local save prepare failed musicId=${item.id}: ${message}`,
            ...previous.logs,
          ],
        }));
      }
    },
    [
      activeDownloadTasks,
      apiClient,
      ensureDownloadDirectory,
      fileSaveProgress,
      libraryItems,
      pendingLocalSave?.item.id,
      refreshLibrary,
      refreshTasks,
      saveAudioUrlToLocalDirectory,
      searchResults,
    ]
  );

  useEffect(() => {
    if (!pendingLocalSave) {
      return;
    }

    const pendingTask = tasks.find(
      (task) =>
        task.type !== "lyrics" &&
        task.musicId === pendingLocalSave.item.id &&
        (!pendingLocalSave.taskId || task.taskId === pendingLocalSave.taskId)
    );
    if (!pendingTask) {
      return;
    }

    const title = compactTitle(
      pendingLocalSave.item.title,
      pendingLocalSave.item.id
    );
    if (pendingTask.status === "failed") {
      setPendingLocalSave(null);
      setSearchMessage(null);
      setSearchError(
        `保存到本地失败：${pendingTask.errorMessage || "服务端下载失败"}`
      );
      return;
    }
    if (pendingTask.status !== "finished") {
      return;
    }
    if (fileSaveProgress?.isSaving) {
      return;
    }

    setPendingLocalSave(null);
    setSearchResults((previousResults) =>
      previousResults.map((result) =>
        result.id === pendingLocalSave.item.id
          ? {
              ...result,
              downloaded: true,
              downloadedFilePath: pendingTask.filePath,
              downloadedFileSize: pendingTask.fileSize,
              downloadedAt: pendingTask.updatedAt,
            }
          : result
      )
    );
    void saveAudioUrlToLocalDirectory({
      musicId: pendingLocalSave.item.id,
      title,
      url: apiClient.taskFileUrl(pendingTask.taskId),
      fileName:
        pendingTask.filename ||
        buildSearchItemFileName(pendingLocalSave.item, title),
      fileSize:
        pendingTask.fileSize ?? pendingLocalSave.item.downloadedFileSize,
      directory: pendingLocalSave.directory,
      feedback: {
        setMessage: setSearchMessage,
        setError: setSearchError,
      },
    }).then((saved) => {
      if (saved) {
        void refreshLibrary(1, { silent: true });
      }
    });
  }, [
    apiClient,
    fileSaveProgress?.isSaving,
    pendingLocalSave,
    refreshLibrary,
    saveAudioUrlToLocalDirectory,
    tasks,
  ]);

  const playDownloadedSong = useCallback(
    async (song: MockDownloadedSong) => {
      const title = compactTitle(
        song.displayTitle || song.filename,
        song.musicId
      );
      await playTrack({
        musicId: song.musicId,
        title,
        subtitle: song.filename,
        durationSec: song.durationSec,
        cover: song.cover,
        source: "downloaded",
        lyricsContent: song.lyricsContent,
      });

      if (!song.lyricsExists) {
        return;
      }

      try {
        const lyrics = await apiClient.getDownloadedSongLyrics(song.musicId);
        if (!lyrics?.content) {
          return;
        }
        setPlayback((previous) =>
          previous.track?.musicId === song.musicId
            ? {
                ...previous,
                track: {
                  ...previous.track,
                  lyricsContent: lyrics.content,
                },
              }
            : previous
        );
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setPlayback((previous) =>
          previous.track?.musicId === song.musicId
            ? {
                ...previous,
                errorMessage: `读取歌词失败：${message}`,
              }
            : previous
        );
      }
    },
    [apiClient, playTrack]
  );

  const handleToggleNativePlayback = useCallback(async () => {
    if (!playback.track || playback.isPreparing || !playback.playbackUrl) {
      return;
    }

    try {
      if (playback.isPlaying) {
        await pauseAudio();
      } else {
        await resumeAudio();
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setPlayback((previous) => ({
        ...previous,
        errorMessage: `播放控制失败：${message}`,
      }));
    }
  }, [
    playback.isPlaying,
    playback.isPreparing,
    playback.playbackUrl,
    playback.track,
  ]);

  const handleSeekNativePlayback = useCallback(
    async (deltaSec: number) => {
      if (!playback.playbackUrl || playback.isPreparing) {
        return;
      }

      try {
        await seekAudioBy(deltaSec);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setPlayback((previous) => ({
          ...previous,
          errorMessage: `跳转播放失败：${message}`,
        }));
      }
    },
    [playback.isPreparing, playback.playbackUrl]
  );

  const currentTitle = routeTitle(route);
  const selectedBottomRoute = isMainRoute(route) ? route : lastMainRoute;

  return (
    <SafeAreaProvider>
      <SafeAreaView className="flex-1 bg-zinc-100" edges={["top", "bottom"]}>
        <View className="flex-1">
          <AppHeader
            title={currentTitle}
            route={route}
            activeTaskCount={activeTaskCount}
            onBack={handleBack}
            onOpenTasks={() => navigate("tasks")}
          />

          <View className="flex-1">
            {route === "home" ? (
              <HomeScreen
                baseUrl={baseUrl}
                health={homeHealth}
                quickActions={QUICK_ACTIONS}
                onNavigate={navigate}
                onRefresh={() => void refreshHealth()}
              />
            ) : null}

            {route === "search" ? (
              <SearchScreen
                input={searchInput}
                errorMessage={searchError}
                isSearching={isSearching}
                onInputChange={setSearchInput}
                onSearch={() => performSearch()}
                onPresetSearch={performSearch}
              />
            ) : null}

            {route === "results" ? (
              <ResultsScreen
                activeKeyword={activeKeyword}
                results={pagedSearchResults}
                totalResults={searchResults.length}
                currentPage={searchPage}
                totalPages={searchTotalPages}
                message={searchMessage}
                errorMessage={searchError}
                isLoading={isSearching}
                activeDownloadTasks={activeDownloadTasks}
                fileSaveProgress={fileSaveProgress}
                pendingLocalSave={pendingLocalSave}
                playback={playback}
                onRetry={() => performSearch(activeKeyword || searchInput)}
                onPreviousPage={() =>
                  setSearchPage((page) => Math.max(1, page - 1))
                }
                onNextPage={() =>
                  setSearchPage((page) => Math.min(searchTotalPages, page + 1))
                }
                onPlay={(item) =>
                  void playTrack({
                    musicId: item.id,
                    title: item.title,
                    subtitle: item.channel,
                    durationSec: item.duration,
                    cover: item.cover,
                    source: "search",
                    lyricsContent: item.lyrics,
                  })
                }
                onDownload={(item) =>
                  startDownload({
                    musicId: item.id,
                    title: item.title,
                    subtitle: item.channel,
                    durationSec: item.duration,
                    cover: item.cover,
                    source: "search",
                    lyricsContent: item.lyrics,
                  })
                }
                onSaveToLocal={(item) => void handleSaveSearchItemToLocal(item)}
              />
            ) : null}

            {route === "charts" ? (
              <ChartsScreen
                title={chartTitle}
                updatedAt={chartUpdatedAt}
                errorMessage={chartError}
                items={chartItems}
                regions={selectedChartRegions}
                selectedRegion={selectedRegion}
                selectedRegionLabel={selectedRegionLabel}
                refreshing={chartRefreshing}
                onRefresh={handleRefreshCharts}
                onSelectRegion={setSelectedRegion}
                onSearch={handleChartSearch}
                onPlay={(item) => void playChartItem(item)}
              />
            ) : null}

            {route === "library" ? (
              <LibraryScreen
                items={pagedLibraryItems}
                totalItems={libraryTotalItems}
                currentPage={libraryPage}
                totalPages={libraryTotalPages}
                message={libraryMessage}
                errorMessage={libraryError}
                activeLyricsTasks={activeLyricsTasks}
                fileSaveProgress={fileSaveProgress}
                playback={playback}
                onRefresh={() => void refreshLibrary(libraryPage)}
                onPreviousPage={() =>
                  void refreshLibrary(Math.max(1, libraryPage - 1))
                }
                onNextPage={() =>
                  void refreshLibrary(
                    Math.min(libraryTotalPages, libraryPage + 1)
                  )
                }
                onPlay={(song) => void playDownloadedSong(song)}
                onDownloadSong={(song) => void handleSaveDownloadedSong(song)}
                onGenerateLyrics={startLyricsGeneration}
              />
            ) : null}

            {route === "player" ? (
              <PlayerScreen
                playback={playback}
                fileSaveProgress={fileSaveProgress}
                onBack={handleBack}
                onTogglePlay={() => void handleToggleNativePlayback()}
                onSeekBy={(deltaSec) => void handleSeekNativePlayback(deltaSec)}
                onToggleSurface={() =>
                  setPlayback((previous) => ({
                    ...previous,
                    surface: previous.surface === "cover" ? "lyrics" : "cover",
                  }))
                }
                onDownload={() => {
                  if (playback.track) {
                    if (playback.track.source === "downloaded") {
                      void handleSaveDownloadedSong({
                        musicId: playback.track.musicId,
                        filePath: "",
                        filename:
                          playback.track.subtitle || playback.track.title,
                        displayTitle: playback.track.title,
                        durationSec: playback.track.durationSec,
                        lyricsExists: Boolean(playback.track.lyricsContent),
                        cover: playback.track.cover,
                      });
                    } else if (playback.track.source === "chart") {
                      void performSearch(playback.track.title);
                    } else {
                      void startDownload(playback.track);
                    }
                  }
                }}
              />
            ) : null}

            {route === "tasks" ? (
              <TasksScreen
                tasks={tasks}
                currentTask={currentTask}
                selectedTab={taskTab}
                onSelectTab={setTaskTab}
                onRefresh={() => void refreshTasks()}
              />
            ) : null}

            {route === "settings" ? (
              <SettingsScreen
                settings={settings}
                onChange={setSettings}
                onSaveApiConfig={handleSaveApiConfig}
                onCheckUpdate={handleCheckAppUpdate}
                onDownloadUpdate={handleDownloadAndInstallAppUpdate}
                onSelectProxy={(name) => void handleSelectProxy(name)}
                onPickDownloadDirectory={() =>
                  void handlePickDownloadDirectory()
                }
                onClearDownloadDirectory={() =>
                  void handleClearDownloadDirectory()
                }
              />
            ) : null}
          </View>

          {playback.track ? (
            <MiniPlayer
              playback={playback}
              onOpen={() => navigate("player")}
              onTogglePlay={() => void handleToggleNativePlayback()}
            />
          ) : null}

          <BottomNavigation
            selectedRoute={selectedBottomRoute}
            onNavigate={navigate}
          />
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function AppHeader({
  title,
  route,
  activeTaskCount,
  onBack,
  onOpenTasks,
}: {
  title: string;
  route: Route;
  activeTaskCount: number;
  onBack: () => void;
  onOpenTasks: () => void;
}) {
  const isDetail = !isMainRoute(route);

  return (
    <View className="border-b border-zinc-200 bg-white px-4 py-3">
      <View className="flex-row items-center justify-between">
        <View className="min-w-0 flex-1 flex-row items-center">
          {isDetail ? (
            <IconButton
              icon={ArrowLeft}
              label="返回"
              onPress={onBack}
              className="mr-2"
            />
          ) : (
            <View className="mr-3 h-9 w-9 items-center justify-center rounded-md bg-zinc-950">
              <Headphones size={20} color="#ffffff" strokeWidth={2.2} />
            </View>
          )}
          <View className="min-w-0 flex-1">
            <Text className="text-xs font-medium text-zinc-500">
              Music Worker
            </Text>
            <Text className="text-xl font-bold text-zinc-950" numberOfLines={1}>
              {title}
            </Text>
          </View>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="任务"
          onPress={onOpenTasks}
          className="ml-3 h-10 min-w-10 items-center justify-center rounded-md border border-zinc-200 bg-white px-3"
        >
          <View className="flex-row items-center">
            <ListMusic size={20} color="#18181b" strokeWidth={2.1} />
            {activeTaskCount > 0 ? (
              <View className="ml-2 min-w-5 items-center rounded-full bg-rose-600 px-1.5 py-0.5">
                <Text className="text-xs font-bold text-white">
                  {activeTaskCount}
                </Text>
              </View>
            ) : null}
          </View>
        </Pressable>
      </View>
    </View>
  );
}

function HomeScreen({
  baseUrl,
  health,
  quickActions,
  onNavigate,
  onRefresh,
}: {
  baseUrl: string;
  health: HealthPayload;
  quickActions: QuickAction[];
  onNavigate: (route: Route) => void;
  onRefresh: () => void;
}) {
  return (
    <ScreenScroll>
      <SectionCard>
        <View className="flex-row items-start justify-between">
          <View className="min-w-0 flex-1 pr-4">
            <Text className="text-sm font-semibold text-zinc-500">
              本地 API
            </Text>
            <Text
              className="mt-1 text-lg font-bold text-zinc-950"
              numberOfLines={1}
            >
              {baseUrl}
            </Text>
          </View>
          <ActionButton
            label="刷新"
            icon={RefreshCcw}
            variant="secondary"
            onPress={onRefresh}
          />
        </View>
      </SectionCard>

      <View className="flex-row gap-3">
        {quickActions.map((action) => (
          <QuickActionButton
            key={action.route}
            action={action}
            onPress={() => onNavigate(action.route)}
          />
        ))}
      </View>

      <View className="flex-row gap-3">
        <StatTile
          label="总任务"
          value={String(health.tasks.total)}
          tone="zinc"
        />
        <StatTile
          label="运行"
          value={String(health.tasks.running)}
          tone="emerald"
        />
        <StatTile
          label="失败"
          value={String(health.tasks.failed)}
          tone="rose"
        />
      </View>

      <SectionCard>
        <SectionTitle icon={Server} title="服务状态" />
        <InfoRow label="服务名" value={health.service.name} />
        <InfoRow
          label="监听"
          value={`${health.service.host}:${health.service.port}`}
        />
        <InfoRow label="FFmpeg" value={health.runtime.ffmpeg || "未配置"} />
        <InfoRow
          label="Cookie"
          value={health.runtime.cookies?.enabled ? "已启用" : "未启用"}
          tone={health.runtime.cookies?.enabled ? "success" : "muted"}
        />
      </SectionCard>

      <SectionCard>
        <SectionTitle icon={Wifi} title="代理节点" />
        <InfoRow label="分组" value={health.proxy.selector || "Proxy"} />
        <InfoRow
          label="当前节点"
          value={health.proxy.name || "未选择"}
          tone={health.proxy.alive ? "success" : "danger"}
        />
        <InfoRow
          label="yt-dlp"
          value={health.runtime.proxy?.ytdlp || "未设置"}
        />
      </SectionCard>
    </ScreenScroll>
  );
}

function SearchScreen({
  input,
  errorMessage,
  isSearching,
  onInputChange,
  onSearch,
  onPresetSearch,
}: {
  input: string;
  errorMessage: string | null;
  isSearching: boolean;
  onInputChange: (value: string) => void;
  onSearch: () => void;
  onPresetSearch: (value: string) => void;
}) {
  const presets = ["Night Drive", "Soft Pulse", "Clear Signal"];

  return (
    <ScreenScroll>
      <SectionCard>
        <SectionTitle icon={Search} title="搜索 YouTube 音乐" />
        <TextInput
          value={input}
          onChangeText={onInputChange}
          multiline
          placeholder="输入歌名、艺人或多行关键词"
          placeholderTextColor="#a1a1aa"
          className="mt-4 min-h-28 rounded-md border border-zinc-300 bg-white px-3 py-3 text-base leading-6 text-zinc-950"
          textAlignVertical="top"
        />
        {errorMessage ? (
          <Text className="mt-3 text-sm font-medium text-rose-600">
            {errorMessage}
          </Text>
        ) : null}
        <View className="mt-4">
          <ActionButton
            label={isSearching ? "搜索中" : "搜索并进入结果页"}
            icon={Search}
            disabled={isSearching}
            onPress={onSearch}
          />
        </View>
      </SectionCard>

      <SectionCard>
        <SectionTitle icon={Music2} title="常用关键词" />
        <View className="mt-3 flex-row flex-wrap gap-2">
          {presets.map((preset) => (
            <Chip
              key={preset}
              label={preset}
              selected={input === preset}
              onPress={() => onPresetSearch(preset)}
            />
          ))}
        </View>
      </SectionCard>
    </ScreenScroll>
  );
}

function ResultsScreen({
  activeKeyword,
  results,
  totalResults,
  currentPage,
  totalPages,
  message,
  errorMessage,
  isLoading,
  activeDownloadTasks,
  fileSaveProgress,
  pendingLocalSave,
  playback,
  onRetry,
  onPreviousPage,
  onNextPage,
  onPlay,
  onDownload,
  onSaveToLocal,
}: {
  activeKeyword: string;
  results: MockSearchItem[];
  totalResults: number;
  currentPage: number;
  totalPages: number;
  message: string | null;
  errorMessage: string | null;
  isLoading: boolean;
  activeDownloadTasks: Map<string, MockTask>;
  fileSaveProgress: FileSaveProgressState | null;
  pendingLocalSave: PendingLocalSaveRequest | null;
  playback: PlaybackState;
  onRetry: () => void;
  onPreviousPage: () => void;
  onNextPage: () => void;
  onPlay: (item: MockSearchItem) => void;
  onDownload: (item: MockSearchItem) => void;
  onSaveToLocal: (item: MockSearchItem) => void;
}) {
  return (
    <ScreenScroll>
      <SectionCard>
        <SectionTitle icon={Search} title="搜索结果" />
        <Text className="mt-2 text-sm text-zinc-600" numberOfLines={2}>
          {activeKeyword || "未发起搜索"}
        </Text>
        <Text className="mt-2 text-sm text-zinc-500">
          共 {totalResults} 条，当前第 {currentPage} / {totalPages} 页
        </Text>
        {message ? (
          <View className="mt-3">
            <StateLine icon={CheckCircle2} text={message} tone="success" />
          </View>
        ) : null}
        {errorMessage ? (
          <View className="mt-3">
            <StateLine icon={XCircle} text={errorMessage} tone="danger" />
            <View className="mt-3">
              <ActionButton
                label="重试搜索"
                icon={RefreshCcw}
                variant="secondary"
                onPress={onRetry}
              />
            </View>
          </View>
        ) : null}
        {isLoading ? (
          <View className="mt-3 flex-row items-center">
            <ActivityIndicator color="#059669" />
            <Text className="ml-2 text-sm font-medium text-zinc-500">
              正在从后端搜索
            </Text>
          </View>
        ) : null}
        {totalPages > 1 ? (
          <PaginationControls
            currentPage={currentPage}
            totalPages={totalPages}
            onPrevious={onPreviousPage}
            onNext={onNextPage}
          />
        ) : null}
      </SectionCard>

      {results.length === 0 && !errorMessage ? (
        <EmptyState
          icon={Search}
          title="暂无结果"
          body="返回搜索页输入关键词。"
        />
      ) : null}

      {results.map((item) => {
        const activeTask = activeDownloadTasks.get(item.id);
        const activeSaveProgress =
          fileSaveProgress?.musicId === item.id ? fileSaveProgress : null;
        const isPendingLocalSave = pendingLocalSave?.item.id === item.id;
        const saveInProgress = Boolean(activeSaveProgress?.isSaving);
        const taskInProgress = Boolean(activeTask);
        const localSaveDisabled = saveInProgress || isPendingLocalSave;
        const localSaveLabel = saveInProgress
          ? "保存中"
          : isPendingLocalSave
          ? "准备中"
          : taskInProgress
          ? "完成后保存"
          : item.downloaded
          ? "保存本地"
          : "下载到本地";

        return (
          <TrackCard
            key={item.id}
            title={item.title}
            subtitle={item.channel || "YouTube Music"}
            cover={item.cover}
            meta={[
              item.duration ? formatDuration(item.duration) : null,
              item.downloaded ? "已下载" : "未下载",
            ]}
            isPlaying={
              playback.track?.musicId === item.id && playback.isPlaying
            }
            progress={
              activeSaveProgress
                ? {
                    value: activeSaveProgress.progress,
                    label: buildFileSaveProgressLabel(activeSaveProgress),
                  }
                : isPendingLocalSave && activeTask
                ? {
                    value: activeTask.progress,
                    label: `${formatTaskStage(activeTask.stage)} · ${
                      activeTask.progress
                    }%`,
                  }
                : undefined
            }
            primaryAction={{
              label:
                playback.track?.musicId === item.id && playback.isPlaying
                  ? "播放中"
                  : "在线播放",
              icon: Play,
              onPress: () => onPlay(item),
            }}
            secondaryAction={{
              label: item.downloaded ? "已下载" : "下载 MP3",
              icon: Download,
              onPress: () => onDownload(item),
              disabled: item.downloaded || taskInProgress,
            }}
            extraAction={{
              label: localSaveLabel,
              icon: FolderOpen,
              onPress: () => onSaveToLocal(item),
              disabled: localSaveDisabled,
            }}
          />
        );
      })}

      {totalPages > 1 ? (
        <PaginationControls
          currentPage={currentPage}
          totalPages={totalPages}
          onPrevious={onPreviousPage}
          onNext={onNextPage}
        />
      ) : null}
    </ScreenScroll>
  );
}

function ChartsScreen({
  title,
  updatedAt,
  errorMessage,
  items,
  regions,
  selectedRegion,
  selectedRegionLabel,
  refreshing,
  onRefresh,
  onSelectRegion,
  onSearch,
  onPlay,
}: {
  title: string;
  updatedAt: string | null;
  errorMessage: string | null;
  items: MockChartItem[];
  regions: { id: string; label: string }[];
  selectedRegion: string;
  selectedRegionLabel: string;
  refreshing: boolean;
  onRefresh: () => void;
  onSelectRegion: (region: string) => void;
  onSearch: (item: MockChartItem) => void;
  onPlay: (item: MockChartItem) => void;
}) {
  return (
    <ScreenScroll>
      <SectionCard>
        <View className="flex-row items-start justify-between">
          <View className="min-w-0 flex-1 pr-3">
            <SectionTitle icon={BarChart3} title="今日排行榜" />
            <Text className="mt-2 text-base font-semibold text-zinc-950">
              {title}
            </Text>
            <Text className="mt-1 text-sm text-zinc-500">
              {selectedRegionLabel} ·{" "}
              {updatedAt ? formatTimestamp(updatedAt) : "未加载"}
            </Text>
          </View>
          {refreshing ? (
            <ActivityIndicator color="#059669" />
          ) : (
            <IconButton icon={RefreshCcw} label="刷新" onPress={onRefresh} />
          )}
        </View>
        {errorMessage ? (
          <View className="mt-3">
            <StateLine icon={XCircle} text={errorMessage} tone="danger" />
          </View>
        ) : null}
      </SectionCard>

      <View className="flex-row flex-wrap gap-2">
        {regions.map((region) => (
          <Chip
            key={region.id}
            label={localizeRegion(region.id, region.label)}
            selected={region.id === selectedRegion}
            onPress={() => onSelectRegion(region.id)}
          />
        ))}
      </View>

      {items.map((item) => (
        <ChartRow
          key={`${item.rank}-${item.musicId}`}
          item={item}
          onSearch={() => onSearch(item)}
          onPlay={() => onPlay(item)}
        />
      ))}
      {items.length === 0 && !refreshing ? (
        <EmptyState
          icon={BarChart3}
          title="暂无榜单"
          body="刷新后端榜单接口后会显示在这里。"
        />
      ) : null}
    </ScreenScroll>
  );
}

function LibraryScreen({
  items,
  totalItems,
  currentPage,
  totalPages,
  message,
  errorMessage,
  activeLyricsTasks,
  fileSaveProgress,
  playback,
  onRefresh,
  onPreviousPage,
  onNextPage,
  onPlay,
  onDownloadSong,
  onGenerateLyrics,
}: {
  items: MockDownloadedSong[];
  totalItems: number;
  currentPage: number;
  totalPages: number;
  message: string | null;
  errorMessage: string | null;
  activeLyricsTasks: Map<string, MockTask>;
  fileSaveProgress: FileSaveProgressState | null;
  playback: PlaybackState;
  onRefresh: () => void;
  onPreviousPage: () => void;
  onNextPage: () => void;
  onPlay: (item: MockDownloadedSong) => void;
  onDownloadSong: (item: MockDownloadedSong) => void;
  onGenerateLyrics: (item: MockDownloadedSong) => void;
}) {
  return (
    <ScreenScroll>
      <SectionCard>
        <View className="flex-row items-start justify-between">
          <View className="min-w-0 flex-1 pr-3">
            <SectionTitle icon={Library} title="已下载歌曲" />
            <Text className="mt-2 text-sm text-zinc-500">
              共 {totalItems} 首，当前第 {currentPage} / {totalPages} 页
            </Text>
          </View>
          <IconButton icon={RefreshCcw} label="刷新" onPress={onRefresh} />
        </View>
        {message ? (
          <View className="mt-3">
            <StateLine icon={CheckCircle2} text={message} tone="success" />
          </View>
        ) : null}
        {errorMessage ? (
          <View className="mt-3">
            <StateLine icon={XCircle} text={errorMessage} tone="danger" />
          </View>
        ) : null}
        {totalPages > 1 ? (
          <PaginationControls
            currentPage={currentPage}
            totalPages={totalPages}
            onPrevious={onPreviousPage}
            onNext={onNextPage}
          />
        ) : null}
      </SectionCard>

      {items.length === 0 ? (
        <EmptyState
          icon={Library}
          title="曲库为空"
          body="下载完成后会出现在这里。"
        />
      ) : null}

      {items.map((item) => {
        const activeLyricsTask = activeLyricsTasks.get(item.musicId);
        const activeSaveProgress =
          fileSaveProgress?.musicId === item.musicId ? fileSaveProgress : null;
        const title = compactTitle(
          item.displayTitle || item.filename,
          item.musicId
        );

        return (
          <TrackCard
            key={item.musicId}
            title={title}
            subtitle={item.filename || item.filePath}
            cover={item.cover}
            meta={[
              item.durationSec ? formatDuration(item.durationSec) : null,
              item.fileSize ? formatBytes(item.fileSize) : null,
              item.lyricsExists ? "LRC 已生成" : "未生成 LRC",
            ]}
            isPlaying={
              playback.track?.musicId === item.musicId && playback.isPlaying
            }
            progress={
              activeSaveProgress
                ? {
                    value: activeSaveProgress.progress,
                    label: buildFileSaveProgressLabel(activeSaveProgress),
                  }
                : activeLyricsTask
                ? {
                    value: activeLyricsTask.progress,
                    label: `${formatTaskStage(activeLyricsTask.stage)} · ${
                      activeLyricsTask.progress
                    }%`,
                  }
                : undefined
            }
            primaryAction={{
              label:
                playback.track?.musicId === item.musicId && playback.isPlaying
                  ? "播放中"
                  : "在线播放",
              icon: Play,
              onPress: () => onPlay(item),
            }}
            secondaryAction={{
              label: activeSaveProgress?.isSaving ? "保存中" : "保存歌曲",
              icon: Download,
              onPress: () => onDownloadSong(item),
              disabled: Boolean(activeSaveProgress?.isSaving),
            }}
            extraAction={
              item.lyricsExists
                ? {
                    label: "LRC 已生成",
                    icon: Mic2,
                    onPress: () => {},
                    disabled: true,
                  }
                : {
                    label: activeLyricsTask ? "生成中" : "生成 LRC",
                    icon: Mic2,
                    onPress: () => onGenerateLyrics(item),
                    disabled: Boolean(activeLyricsTask),
                  }
            }
          />
        );
      })}
    </ScreenScroll>
  );
}

function PlayerScreen({
  playback,
  fileSaveProgress,
  onBack,
  onTogglePlay,
  onSeekBy,
  onToggleSurface,
  onDownload,
}: {
  playback: PlaybackState;
  fileSaveProgress: FileSaveProgressState | null;
  onBack: () => void;
  onTogglePlay: () => void;
  onSeekBy: (deltaSec: number) => void;
  onToggleSurface: () => void;
  onDownload: () => void;
}) {
  const track = playback.track;
  const durationSec = track?.durationSec || 0;
  const progress =
    durationSec > 0 ? (playback.positionSec / durationSec) * 100 : 0;
  const canControl = Boolean(playback.playbackUrl) && !playback.isPreparing;
  const activeSaveProgress =
    track && fileSaveProgress?.musicId === track.musicId
      ? fileSaveProgress
      : null;
  const lyricLines = useMemo(
    () => parseLyrics(track?.lyricsContent || ""),
    [track?.lyricsContent]
  );

  if (!track) {
    return (
      <ScreenScroll>
        <EmptyState
          icon={Music2}
          title="未选择歌曲"
          body="从搜索、榜单或曲库开始播放。"
        />
        <ActionButton
          label="返回"
          icon={ArrowLeft}
          variant="secondary"
          onPress={onBack}
        />
      </ScreenScroll>
    );
  }

  return (
    <ScreenScroll>
      <SectionCard>
        <Pressable onPress={onToggleSurface}>
          {playback.surface === "cover" ? (
            <View className="items-center">
              <CoverImage uri={track.cover} size="large" />
              <Text className="mt-3 text-xs font-medium text-zinc-500">
                封面 / 歌词
              </Text>
            </View>
          ) : (
            <LyricsPanel
              lines={lyricLines}
              positionSec={playback.positionSec}
            />
          )}
        </Pressable>
      </SectionCard>

      <View className="items-center px-3">
        <Text
          className="text-center text-2xl font-bold text-zinc-950"
          numberOfLines={3}
        >
          {track.title}
        </Text>
        {track.subtitle ? (
          <Text
            className="mt-2 text-center text-base text-zinc-500"
            numberOfLines={1}
          >
            {track.subtitle}
          </Text>
        ) : null}
        <View className="mt-3 flex-row flex-wrap justify-center gap-2">
          <MetaPill
            label={track.source === "downloaded" ? "已下载串流" : "服务端串流"}
          />
          {durationSec ? (
            <MetaPill label={formatDuration(durationSec)} />
          ) : null}
          {track.lyricsContent ? <MetaPill label="同步歌词" /> : null}
        </View>
      </View>

      <SectionCard>
        <View className="mb-2 flex-row items-center justify-between">
          <Text className="text-xs font-medium text-zinc-500">
            {formatClock(playback.positionSec)}
          </Text>
          <Text className="text-xs font-medium text-zinc-500">
            {formatClock(durationSec)}
          </Text>
        </View>
        <ProgressBar value={progress} />
        <View className="mt-5 flex-row items-center justify-center gap-4">
          <IconButton
            icon={SkipBack}
            label="后退 10 秒"
            onPress={() => onSeekBy(-10)}
          />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={
              playback.isPreparing
                ? "准备中"
                : playback.isPlaying
                ? "暂停"
                : "播放"
            }
            onPress={canControl ? onTogglePlay : undefined}
            className={cn(
              "h-14 w-14 items-center justify-center rounded-full bg-zinc-950",
              !canControl && "opacity-45"
            )}
          >
            {playback.isPreparing ? (
              <ActivityIndicator color="#ffffff" />
            ) : playback.isPlaying ? (
              <Pause size={26} color="#ffffff" fill="#ffffff" />
            ) : (
              <Play size={27} color="#ffffff" fill="#ffffff" />
            )}
          </Pressable>
          <IconButton
            icon={SkipForward}
            label="前进 10 秒"
            onPress={() => onSeekBy(10)}
          />
        </View>
        <View className="mt-5">
          <ActionButton
            label={
              activeSaveProgress?.isSaving
                ? "保存中"
                : track.source === "downloaded"
                ? "保存到下载目录"
                : "下载 MP3"
            }
            icon={Download}
            variant="secondary"
            onPress={onDownload}
            disabled={Boolean(activeSaveProgress?.isSaving)}
          />
        </View>
      </SectionCard>

      {activeSaveProgress ? (
        <SectionCard>
          <ProgressBar value={activeSaveProgress.progress} />
          <Text className="mt-2 text-sm font-semibold text-emerald-700">
            {buildFileSaveProgressLabel(activeSaveProgress)}
          </Text>
        </SectionCard>
      ) : null}

      {playback.message ? (
        <StateLine icon={CheckCircle2} text={playback.message} tone="success" />
      ) : null}
      {playback.errorMessage ? (
        <StateLine icon={XCircle} text={playback.errorMessage} tone="danger" />
      ) : null}
    </ScreenScroll>
  );
}

function TasksScreen({
  tasks,
  currentTask,
  selectedTab,
  onSelectTab,
  onRefresh,
}: {
  tasks: MockTask[];
  currentTask: MockTask | null;
  selectedTab: TaskTab;
  onSelectTab: (tab: TaskTab) => void;
  onRefresh: () => void;
}) {
  const shownTasks = tasks.filter((task) =>
    selectedTab === "lyrics" ? task.type === "lyrics" : task.type !== "lyrics"
  );

  return (
    <ScreenScroll>
      <SectionCard>
        <View className="flex-row items-start justify-between">
          <View className="min-w-0 flex-1 pr-3">
            <SectionTitle icon={ListMusic} title="任务中心" />
            <Text className="mt-2 text-sm text-zinc-500">
              {shownTasks.length} 条记录
            </Text>
          </View>
          <IconButton icon={RefreshCcw} label="刷新" onPress={onRefresh} />
        </View>
        <View className="mt-4 flex-row rounded-md bg-zinc-100 p-1">
          <SegmentButton
            label="歌曲下载"
            selected={selectedTab === "download"}
            onPress={() => onSelectTab("download")}
          />
          <SegmentButton
            label="歌词生成"
            selected={selectedTab === "lyrics"}
            onPress={() => onSelectTab("lyrics")}
          />
        </View>
      </SectionCard>

      {currentTask &&
      currentTask.type !== "lyrics" &&
      selectedTab === "download" ? (
        <TaskCard task={currentTask} titlePrefix="当前歌曲下载" highlighted />
      ) : null}

      {shownTasks.length === 0 ? (
        <EmptyState
          icon={ListMusic}
          title="暂无任务"
          body="任务创建后会显示在这里。"
        />
      ) : null}

      {shownTasks
        .filter((task) => task.taskId !== currentTask?.taskId)
        .map((task) => (
          <TaskCard key={task.taskId} task={task} />
        ))}
    </ScreenScroll>
  );
}

function SettingsScreen({
  settings,
  onChange,
  onSaveApiConfig,
  onCheckUpdate,
  onDownloadUpdate,
  onSelectProxy,
  onPickDownloadDirectory,
  onClearDownloadDirectory,
}: {
  settings: SettingsState;
  onChange: React.Dispatch<React.SetStateAction<SettingsState>>;
  onSaveApiConfig: () => void;
  onCheckUpdate: () => void;
  onDownloadUpdate: () => void;
  onSelectProxy: (name: string) => void;
  onPickDownloadDirectory: () => void;
  onClearDownloadDirectory: () => void;
}) {
  return (
    <ScreenScroll>
      <SectionCard>
        <SectionTitle icon={Server} title="本地 API 配置" />
        <Text className="mt-2 text-sm text-zinc-500">
          http://{settings.host || "127.0.0.1"}:{settings.port || "18081"}
        </Text>
        <TextInput
          value={settings.host}
          onChangeText={(value) =>
            onChange((previous) => ({ ...previous, host: value }))
          }
          className="mt-4 rounded-md border border-zinc-300 bg-white px-3 py-2 text-base text-zinc-950"
          placeholder="Host"
          placeholderTextColor="#a1a1aa"
          autoCapitalize="none"
        />
        <TextInput
          value={settings.port}
          onChangeText={(value) =>
            onChange((previous) => ({ ...previous, port: value }))
          }
          className="mt-3 rounded-md border border-zinc-300 bg-white px-3 py-2 text-base text-zinc-950"
          placeholder="端口"
          placeholderTextColor="#a1a1aa"
          keyboardType="number-pad"
        />
        <View className="mt-4">
          <ActionButton
            label="保存并刷新"
            icon={RefreshCcw}
            onPress={onSaveApiConfig}
          />
        </View>
        {settings.message ? (
          <View className="mt-3">
            <StateLine
              icon={CheckCircle2}
              text={settings.message}
              tone="success"
            />
          </View>
        ) : null}
        {settings.errorMessage ? (
          <View className="mt-3">
            <StateLine
              icon={XCircle}
              text={settings.errorMessage}
              tone="danger"
            />
          </View>
        ) : null}
      </SectionCard>

      <SectionCard>
        <SectionTitle icon={ShieldCheck} title="App 更新" />
        <InfoRow
          label="当前版本"
          value={`${settings.installedVersionName} (${settings.installedVersionCode})`}
        />
        {settings.availableUpdate ? (
          <>
            <InfoRow
              label="可用版本"
              value={`${settings.availableUpdate.versionName} (${settings.availableUpdate.versionCode})`}
              tone="success"
            />
            <InfoRow
              label="安装包"
              value={`${settings.availableUpdate.fileName} / ${formatBytes(
                settings.availableUpdate.fileSize
              )}`}
            />
            <InfoRow
              label="sha256"
              value={settings.availableUpdate.sha256 || "未提供"}
            />
          </>
        ) : (
          <InfoRow label="可用版本" value="未检查" />
        )}
        {settings.isDownloadingUpdate ? (
          <View className="mt-3">
            <ProgressBar value={settings.updateProgress} />
            <Text className="mt-2 text-xs font-medium text-zinc-500">
              下载进度 {settings.updateProgress}% ·{" "}
              {formatBytes(settings.updateDownloadedBytes)}
              {settings.updateTotalBytes
                ? ` / ${formatBytes(settings.updateTotalBytes)}`
                : ""}
            </Text>
          </View>
        ) : null}
        <View className="mt-4 gap-3">
          <ActionButton
            label={settings.isCheckingUpdate ? "检查中" : "检查更新"}
            icon={RefreshCcw}
            variant="secondary"
            disabled={settings.isCheckingUpdate || settings.isDownloadingUpdate}
            onPress={onCheckUpdate}
          />
          <ActionButton
            label={
              settings.updateProgress >= 100
                ? "安装已下载更新"
                : "下载并安装更新"
            }
            icon={Download}
            disabled={!settings.availableUpdate || settings.isDownloadingUpdate}
            onPress={onDownloadUpdate}
          />
        </View>
      </SectionCard>

      <SectionCard>
        <SectionTitle icon={Trash2} title="应用私有目录" />
        <InfoRow
          label="可清理空间"
          value={formatBytes(settings.privateStorageBytes)}
        />
        <View className="mt-4">
          <ActionButton
            label="清理私有目录"
            icon={Trash2}
            variant="secondary"
            onPress={() =>
              onChange((previous) => ({
                ...previous,
                privateStorageBytes: 0,
                message: "私有目录已清理",
                logs: [
                  `[${formatNow()}] private storage cleaned`,
                  ...previous.logs,
                ],
              }))
            }
          />
        </View>
      </SectionCard>

      <SectionCard>
        <SectionTitle icon={FolderOpen} title="下载目录" />
        <InfoRow label="当前目录" value={settings.directoryLabel || "未选择"} />
        {settings.directoryUri ? (
          <Text className="mt-2 text-xs text-zinc-500" numberOfLines={2}>
            {settings.directoryUri}
          </Text>
        ) : null}
        <View className="mt-4 gap-3">
          <ActionButton
            label="选择下载目录"
            icon={FolderOpen}
            onPress={onPickDownloadDirectory}
          />
          {settings.directoryUri ? (
            <ActionButton
              label="清除下载目录"
              icon={Trash2}
              variant="secondary"
              onPress={onClearDownloadDirectory}
            />
          ) : null}
        </View>
      </SectionCard>

      <SectionCard>
        <SectionTitle icon={Wifi} title="代理节点" />
        <InfoRow label="分组" value={settings.proxySelector} />
        <InfoRow
          label="当前节点"
          value={settings.proxyName}
          tone={settings.proxyAlive ? "success" : "danger"}
        />
        <View className="mt-3 flex-row flex-wrap gap-2">
          {settings.proxyOptions.map((name) => (
            <Chip
              key={name}
              label={name}
              selected={name === settings.proxyName}
              onPress={() => onSelectProxy(name)}
            />
          ))}
        </View>
      </SectionCard>

      <SectionCard>
        <SectionTitle icon={ListMusic} title="最近日志" />
        <View className="mt-3 gap-2">
          {settings.logs.slice(0, 12).map((line, index) => (
            <Text
              key={`${line}-${index}`}
              className="font-mono text-xs leading-5 text-zinc-600"
            >
              {line}
            </Text>
          ))}
        </View>
      </SectionCard>
    </ScreenScroll>
  );
}

function MiniPlayer({
  playback,
  onOpen,
  onTogglePlay,
}: {
  playback: PlaybackState;
  onOpen: () => void;
  onTogglePlay: () => void;
}) {
  const track = playback.track;
  if (!track) {
    return null;
  }

  return (
    <View className="border-t border-zinc-200 bg-white px-4 py-2">
      <Pressable className="flex-row items-center" onPress={onOpen}>
        <CoverImage uri={track.cover} size="mini" />
        <View className="ml-3 min-w-0 flex-1">
          <Text
            className="text-sm font-semibold text-zinc-950"
            numberOfLines={1}
          >
            {track.title}
          </Text>
          <Text className="mt-0.5 text-xs text-zinc-500" numberOfLines={1}>
            {track.subtitle || (playback.isPlaying ? "正在播放" : "已暂停")}
          </Text>
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={playback.isPlaying ? "暂停" : "播放"}
          onPress={(event) => {
            event.stopPropagation();
            onTogglePlay();
          }}
          className="ml-3 h-10 w-10 items-center justify-center rounded-full bg-zinc-950"
        >
          {playback.isPlaying ? (
            <Pause size={19} color="#ffffff" fill="#ffffff" />
          ) : (
            <Play size={20} color="#ffffff" fill="#ffffff" />
          )}
        </Pressable>
      </Pressable>
    </View>
  );
}

function BottomNavigation({
  selectedRoute,
  onNavigate,
}: {
  selectedRoute: MainRoute;
  onNavigate: (route: Route) => void;
}) {
  return (
    <View className="border-t border-zinc-200 bg-white px-2 pb-1 pt-1">
      <View className="flex-row items-center justify-between">
        {MAIN_NAV.map((item) => {
          const Icon = item.icon;
          const selected = selectedRoute === item.route;
          const color = selected ? "#059669" : "#71717a";

          return (
            <Pressable
              key={item.route}
              accessibilityRole="button"
              accessibilityLabel={item.label}
              onPress={() => onNavigate(item.route)}
              className={cn(
                "h-14 flex-1 items-center justify-center rounded-md",
                selected && "bg-emerald-50"
              )}
            >
              <Icon size={20} color={color} strokeWidth={2.1} />
              <Text
                className={cn(
                  "mt-1 text-[11px] font-medium",
                  selected ? "text-emerald-700" : "text-zinc-500"
                )}
              >
                {item.label}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

function ScreenScroll({ children }: { children: React.ReactNode }) {
  return (
    <ScrollView
      className="flex-1"
      contentContainerClassName="gap-3 px-4 pb-5 pt-3"
      showsVerticalScrollIndicator={false}
    >
      {children}
    </ScrollView>
  );
}

function SectionCard({ children }: { children: React.ReactNode }) {
  return (
    <View className="rounded-lg border border-zinc-200 bg-white p-4">
      {children}
    </View>
  );
}

function SectionTitle({
  icon: Icon,
  title,
}: {
  icon: IconComponent;
  title: string;
}) {
  return (
    <View className="flex-row items-center">
      <View className="mr-2 h-8 w-8 items-center justify-center rounded-md bg-zinc-100">
        <Icon size={18} color="#18181b" strokeWidth={2.1} />
      </View>
      <Text className="text-base font-bold text-zinc-950">{title}</Text>
    </View>
  );
}

function QuickActionButton({
  action,
  onPress,
}: {
  action: QuickAction;
  onPress: () => void;
}) {
  const Icon = action.icon;
  const colors = {
    emerald: "border-emerald-200 bg-emerald-50",
    sky: "border-sky-200 bg-sky-50",
    amber: "border-amber-200 bg-amber-50",
  }[action.tone];
  const iconColors = {
    emerald: "#059669",
    sky: "#0284c7",
    amber: "#b45309",
  }[action.tone];

  return (
    <Pressable
      onPress={onPress}
      className={cn("min-h-24 flex-1 rounded-lg border p-3", colors)}
    >
      <Icon size={22} color={iconColors} strokeWidth={2.2} />
      <Text className="mt-3 text-sm font-bold text-zinc-950" numberOfLines={2}>
        {action.label}
      </Text>
    </Pressable>
  );
}

function StatTile({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone: "zinc" | "emerald" | "rose";
}) {
  const toneClass = {
    zinc: "border-zinc-200 bg-white",
    emerald: "border-emerald-200 bg-emerald-50",
    rose: "border-rose-200 bg-rose-50",
  }[tone];
  const textClass = {
    zinc: "text-zinc-950",
    emerald: "text-emerald-700",
    rose: "text-rose-700",
  }[tone];

  return (
    <View className={cn("flex-1 rounded-lg border p-3", toneClass)}>
      <Text className="text-xs font-medium text-zinc-500">{label}</Text>
      <Text className={cn("mt-1 text-2xl font-bold", textClass)}>{value}</Text>
    </View>
  );
}

function InfoRow({
  label,
  value,
  tone = "default",
}: {
  label: string;
  value: string;
  tone?: "default" | "success" | "danger" | "muted";
}) {
  const valueClass = {
    default: "text-zinc-950",
    success: "text-emerald-700",
    danger: "text-rose-700",
    muted: "text-zinc-500",
  }[tone];

  return (
    <View className="mt-3 flex-row items-start justify-between gap-4">
      <Text className="text-sm text-zinc-500">{label}</Text>
      <Text
        className={cn(
          "min-w-0 flex-1 text-right text-sm font-semibold",
          valueClass
        )}
        numberOfLines={2}
      >
        {value}
      </Text>
    </View>
  );
}

function TrackCard({
  title,
  subtitle,
  cover,
  meta,
  isPlaying,
  progress,
  primaryAction,
  secondaryAction,
  extraAction,
}: {
  title: string;
  subtitle?: string | null;
  cover?: string | null;
  meta: Array<string | null | undefined>;
  isPlaying?: boolean;
  progress?: { value: number; label: string };
  primaryAction: ButtonAction;
  secondaryAction?: ButtonAction;
  extraAction?: ButtonAction;
}) {
  return (
    <View className="rounded-lg border border-zinc-200 bg-white p-3">
      <View className="flex-row">
        <CoverImage uri={cover} />
        <View className="ml-3 min-w-0 flex-1">
          <View className="flex-row items-start justify-between">
            <View className="min-w-0 flex-1 pr-2">
              <Text
                className="text-base font-bold text-zinc-950"
                numberOfLines={2}
              >
                {title}
              </Text>
              {subtitle ? (
                <Text className="mt-1 text-sm text-zinc-500" numberOfLines={1}>
                  {subtitle}
                </Text>
              ) : null}
            </View>
            {isPlaying ? <PlayingBadge /> : null}
          </View>
          <View className="mt-2 flex-row flex-wrap gap-1.5">
            {meta.filter(Boolean).map((value) => (
              <MetaPill key={value} label={value || ""} />
            ))}
          </View>
        </View>
      </View>

      {progress ? (
        <View className="mt-3">
          <ProgressBar value={progress.value} />
          <Text className="mt-1 text-xs font-medium text-emerald-700">
            {progress.label}
          </Text>
        </View>
      ) : null}

      <View className="mt-3 flex-row gap-2">
        <View className="flex-1">
          <ActionButton {...primaryAction} compact />
        </View>
        {secondaryAction ? (
          <View className="flex-1">
            <ActionButton {...secondaryAction} compact variant="secondary" />
          </View>
        ) : null}
      </View>
      {extraAction ? (
        <View className="mt-2">
          <ActionButton {...extraAction} compact variant="secondary" />
        </View>
      ) : null}
    </View>
  );
}

function ChartRow({
  item,
  onSearch,
  onPlay,
}: {
  item: MockChartItem;
  onSearch: () => void;
  onPlay: () => void;
}) {
  return (
    <View className="rounded-lg border border-zinc-200 bg-white p-3">
      <View className="flex-row">
        <View className="mr-3 w-8 items-center pt-1">
          <Text className="text-lg font-bold text-zinc-950">#{item.rank}</Text>
        </View>
        <CoverImage uri={item.cover} />
        <View className="ml-3 min-w-0 flex-1">
          <Text className="text-base font-bold text-zinc-950" numberOfLines={2}>
            {item.title}
          </Text>
          <Text className="mt-1 text-sm text-zinc-500" numberOfLines={1}>
            {item.artist}
          </Text>
          <View className="mt-2 flex-row flex-wrap gap-1.5">
            {item.album ? <MetaPill label={item.album} /> : null}
            {item.durationSec ? (
              <MetaPill label={formatDuration(item.durationSec)} />
            ) : null}
          </View>
        </View>
      </View>
      <View className="mt-3 flex-row gap-2">
        <View className="flex-1">
          <ActionButton label="在线播放" icon={Play} compact onPress={onPlay} />
        </View>
        <View className="flex-1">
          <ActionButton
            label="搜索"
            icon={Search}
            compact
            variant="secondary"
            onPress={onSearch}
          />
        </View>
      </View>
    </View>
  );
}

function TaskCard({
  task,
  titlePrefix,
  highlighted,
}: {
  task: MockTask;
  titlePrefix?: string;
  highlighted?: boolean;
}) {
  return (
    <View
      className={cn(
        "rounded-lg border bg-white p-4",
        highlighted ? "border-emerald-300" : "border-zinc-200"
      )}
    >
      {titlePrefix ? (
        <Text className="mb-2 text-sm font-semibold text-emerald-700">
          {titlePrefix}
        </Text>
      ) : null}
      <Text className="text-base font-bold text-zinc-950" numberOfLines={2}>
        {task.title || task.filename || task.musicId}
      </Text>
      <Text className="mt-1 text-sm text-zinc-500">
        {formatTaskStatus(task.status)} / {formatTaskStage(task.stage)}
      </Text>
      <View className="mt-3">
        <ProgressBar value={task.progress} />
      </View>
      <View className="mt-2 flex-row flex-wrap gap-1.5">
        <MetaPill label={`${task.progress}%`} />
        {task.speedBps ? <MetaPill label={formatSpeed(task.speedBps)} /> : null}
        {task.fileSize ? <MetaPill label={formatBytes(task.fileSize)} /> : null}
      </View>
      {task.updatedAt ? (
        <Text className="mt-2 text-xs text-zinc-500">
          更新于 {formatTimestamp(task.updatedAt)}
        </Text>
      ) : null}
      {task.errorMessage ? (
        <Text className="mt-2 text-sm font-medium text-rose-600">
          {task.errorMessage}
        </Text>
      ) : null}
    </View>
  );
}

type ButtonAction = {
  label: string;
  icon: IconComponent;
  onPress: () => void;
  disabled?: boolean;
};

function ActionButton({
  label,
  icon: Icon,
  onPress,
  disabled,
  variant = "primary",
  compact,
}: ButtonAction & {
  variant?: "primary" | "secondary";
  compact?: boolean;
}) {
  const isPrimary = variant === "primary";

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={disabled ? undefined : onPress}
      className={cn(
        "min-h-10 flex-row items-center justify-center rounded-md px-3",
        compact ? "py-2" : "py-3",
        isPrimary ? "bg-zinc-950" : "border border-zinc-200 bg-white",
        disabled && "opacity-45"
      )}
    >
      <Icon
        size={compact ? 17 : 18}
        color={isPrimary ? "#ffffff" : "#18181b"}
        strokeWidth={2.2}
      />
      <Text
        className={cn(
          "ml-2 text-sm font-bold",
          isPrimary ? "text-white" : "text-zinc-950"
        )}
        numberOfLines={1}
      >
        {label}
      </Text>
    </Pressable>
  );
}

function IconButton({
  icon: Icon,
  label,
  onPress,
  className,
}: {
  icon: IconComponent;
  label: string;
  onPress: () => void;
  className?: string;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      className={cn(
        "h-10 w-10 items-center justify-center rounded-md border border-zinc-200 bg-white",
        className
      )}
    >
      <Icon size={20} color="#18181b" strokeWidth={2.1} />
    </Pressable>
  );
}

function Chip({
  label,
  selected,
  onPress,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      className={cn(
        "min-h-9 rounded-md border px-3 py-2",
        selected
          ? "border-emerald-600 bg-emerald-50"
          : "border-zinc-200 bg-white"
      )}
    >
      <Text
        className={cn(
          "text-sm font-semibold",
          selected ? "text-emerald-700" : "text-zinc-700"
        )}
      >
        {label}
      </Text>
    </Pressable>
  );
}

function SegmentButton({
  label,
  selected,
  onPress,
}: {
  label: string;
  selected: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      className={cn(
        "min-h-10 flex-1 items-center justify-center rounded-md px-3 py-2",
        selected && "bg-white"
      )}
    >
      <Text
        className={cn(
          "text-sm font-bold",
          selected ? "text-zinc-950" : "text-zinc-500"
        )}
      >
        {label}
      </Text>
    </Pressable>
  );
}

function PaginationControls({
  currentPage,
  totalPages,
  onPrevious,
  onNext,
}: {
  currentPage: number;
  totalPages: number;
  onPrevious: () => void;
  onNext: () => void;
}) {
  return (
    <View className="mt-4 flex-row items-center gap-3">
      <View className="flex-1">
        <ActionButton
          label="上一页"
          icon={ChevronLeft}
          variant="secondary"
          disabled={currentPage <= 1}
          onPress={onPrevious}
        />
      </View>
      <View className="flex-1">
        <ActionButton
          label="下一页"
          icon={ChevronRight}
          disabled={currentPage >= totalPages}
          onPress={onNext}
        />
      </View>
    </View>
  );
}

function EmptyState({
  icon: Icon,
  title,
  body,
}: {
  icon: IconComponent;
  title: string;
  body: string;
}) {
  return (
    <View className="items-center rounded-lg border border-zinc-200 bg-white px-6 py-8">
      <View className="h-12 w-12 items-center justify-center rounded-md bg-zinc-100">
        <Icon size={24} color="#71717a" strokeWidth={2.1} />
      </View>
      <Text className="mt-3 text-base font-bold text-zinc-950">{title}</Text>
      <Text className="mt-1 text-center text-sm text-zinc-500">{body}</Text>
    </View>
  );
}

function StateLine({
  icon: Icon,
  text,
  tone,
}: {
  icon: IconComponent;
  text: string;
  tone: "success" | "danger";
}) {
  const color = tone === "success" ? "#059669" : "#e11d48";
  const className = tone === "success" ? "text-emerald-700" : "text-rose-700";

  return (
    <View className="flex-row items-center rounded-md bg-zinc-50 px-3 py-2">
      <Icon size={17} color={color} strokeWidth={2.2} />
      <Text className={cn("ml-2 flex-1 text-sm font-semibold", className)}>
        {text}
      </Text>
    </View>
  );
}

function PlayingBadge() {
  return (
    <View className="rounded-full bg-emerald-100 px-2 py-1">
      <Text className="text-xs font-bold text-emerald-700">播放中</Text>
    </View>
  );
}

function MetaPill({ label }: { label: string }) {
  return (
    <View className="rounded-md bg-zinc-100 px-2 py-1">
      <Text className="text-xs font-medium text-zinc-600" numberOfLines={1}>
        {label}
      </Text>
    </View>
  );
}

function ProgressBar({ value }: { value: number }) {
  const normalized = Math.max(0, Math.min(100, value));

  return (
    <View className="h-2 overflow-hidden rounded-full bg-zinc-200">
      <View
        className="h-full rounded-full bg-emerald-600"
        style={{ width: `${normalized}%` }}
      />
    </View>
  );
}

function buildFileSaveProgressLabel(progress: FileSaveProgressState): string {
  const percent = `${Math.max(0, Math.min(100, progress.progress))}%`;
  if (progress.totalBytes && progress.totalBytes > 0) {
    return `保存中 ${percent} · ${formatBytes(progress.downloadedBytes)} / ${formatBytes(
      progress.totalBytes
    )}`;
  }
  if (progress.downloadedBytes > 0) {
    return `保存中 ${percent} · ${formatBytes(progress.downloadedBytes)}`;
  }
  return `保存中 ${percent}`;
}

function CoverImage({
  uri,
  size = "normal",
}: {
  uri?: string | null;
  size?: "mini" | "normal" | "large";
}) {
  const sizeClass = {
    mini: "h-11 w-11 rounded-md",
    normal: "h-20 w-20 rounded-md",
    large: "h-72 w-full rounded-lg",
  }[size];

  if (!uri) {
    return (
      <View
        className={cn(sizeClass, "items-center justify-center bg-zinc-200")}
      >
        <Music2 size={24} color="#71717a" strokeWidth={2.1} />
      </View>
    );
  }

  return (
    <Image
      source={{ uri }}
      className={cn(sizeClass, "bg-zinc-200")}
      resizeMode="cover"
    />
  );
}

function LyricsPanel({
  lines,
  positionSec,
}: {
  lines: Array<{ timeSec: number; text: string }>;
  positionSec: number;
}) {
  const activeIndex = findActiveLyricLine(lines, positionSec);

  if (lines.length === 0) {
    return (
      <View className="h-72 items-center justify-center rounded-lg bg-zinc-100 px-5">
        <Mic2 size={28} color="#71717a" strokeWidth={2.1} />
        <Text className="mt-3 text-sm font-medium text-zinc-500">
          暂无同步歌词
        </Text>
      </View>
    );
  }

  return (
    <View className="h-72 rounded-lg bg-zinc-100 px-4 py-4">
      <ScrollView showsVerticalScrollIndicator={false}>
        {lines.map((line, index) => {
          const active = index === activeIndex;
          return (
            <Text
              key={`${line.timeSec}-${line.text}`}
              className={cn(
                "py-2 text-center",
                active
                  ? "text-lg font-bold text-emerald-700"
                  : "text-base text-zinc-600"
              )}
            >
              {line.text}
            </Text>
          );
        })}
      </ScrollView>
    </View>
  );
}

function createEmptyHealth(): HealthPayload {
  return {
    service: {
      name: "music_local_api",
      host: DEFAULT_API_HOST,
      port: DEFAULT_API_PORT,
    },
    runtime: {},
    tasks: {
      total: 0,
      queued: 0,
      running: 0,
      finished: 0,
      failed: 0,
    },
    proxy: {
      selector: "Proxy",
      name: null,
      alive: false,
      options: [],
    },
  };
}

function applyProxyInfo(
  proxy: ProxyInfo,
  setSettings: React.Dispatch<React.SetStateAction<SettingsState>>
) {
  setSettings((previous) => ({
    ...previous,
    proxyName: proxy.name || "未选择",
    proxySelector: proxy.selector || "Proxy",
    proxyAlive: Boolean(proxy.alive),
    proxyOptions: proxy.options || [],
  }));
}

function buildTaskStats(tasks: MockTask[]) {
  return tasks.reduce(
    (stats, task) => {
      stats.total += 1;
      if (task.status === "queued") {
        stats.queued += 1;
      } else if (task.status === "running") {
        stats.running += 1;
      } else if (task.status === "finished") {
        stats.finished += 1;
      } else if (task.status === "failed") {
        stats.failed += 1;
      }
      return stats;
    },
    { total: 0, queued: 0, running: 0, finished: 0, failed: 0 }
  );
}

function buildSearchItemFileName(item: MockSearchItem, fallbackTitle: string) {
  const baseName = compactTitle(
    item.title || item.channel
      ? [item.channel, item.title].filter(Boolean).join(" - ")
      : fallbackTitle,
    fallbackTitle
  );
  return baseName.toLowerCase().endsWith(".mp3") ? baseName : `${baseName}.mp3`;
}

function chartItemFromBackend(
  item: ChartItem,
  sourceId: string,
  index: number
): MockChartItem {
  const rank = item.rank || index + 1;
  const searchKeyword =
    item.searchKeyword || `${item.artist || ""} ${item.title || ""}`.trim();
  return {
    ...item,
    rank,
    searchKeyword,
    sourceId: item.sourceId || sourceId,
    musicId:
      item.deeplink ||
      `${sourceId || "chart"}-${rank}-${searchKeyword || item.title}`,
  };
}

function downloadedSongFromBackend(
  item: DownloadedSongItem,
  knownSearchResults: SearchItem[]
): MockDownloadedSong {
  const searchMatch = knownSearchResults.find(
    (result) => result.id === item.musicId
  );
  return {
    ...item,
    cover: searchMatch?.cover || null,
  };
}

function taskFromBackend(
  task: DownloadTask,
  knownSearchResults: SearchItem[],
  libraryItems: MockDownloadedSong[],
  track?: PlaybackTrack
): MockTask {
  const searchMatch = knownSearchResults.find(
    (result) => result.id === task.musicId
  );
  const libraryMatch = libraryItems.find(
    (item) => item.musicId === task.musicId
  );
  const title =
    track?.title ||
    searchMatch?.title ||
    compactTitle(libraryMatch?.displayTitle || task.filename, task.musicId);

  return {
    ...task,
    type: task.type || "download",
    title,
    cover: track?.cover || searchMatch?.cover || libraryMatch?.cover || null,
  };
}

function upsertTask(tasks: MockTask[], task: MockTask): MockTask[] {
  const existingIndex = tasks.findIndex((item) => item.taskId === task.taskId);
  if (existingIndex < 0) {
    return [task, ...tasks];
  }
  const nextTasks = [...tasks];
  nextTasks[existingIndex] = task;
  return nextTasks;
}

function applyAudioStatusToPlayback(
  state: PlaybackState,
  event: AudioPlayerStatusEvent
): PlaybackState {
  if (!state.track) {
    return state;
  }

  const nextDurationSec =
    event.durationSec > 0 ? event.durationSec : state.track.durationSec;
  return {
    ...state,
    track: {
      ...state.track,
      durationSec: nextDurationSec,
    },
    isPlaying: event.isPlaying,
    isPreparing: event.isBuffering && !event.isPrepared,
    positionSec: Math.max(0, event.positionSec || 0),
    playbackUrl: event.url || state.playbackUrl,
    message:
      event.errorMessage != null
        ? null
        : event.message != null
        ? event.message
        : state.message,
    errorMessage: event.errorMessage ?? state.errorMessage,
  };
}

function parseLyrics(
  content: string
): Array<{ timeSec: number; text: string }> {
  return content
    .split(/\r?\n/)
    .map((line) => {
      const match = line.match(/\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?](.*)/);
      if (!match) {
        return null;
      }
      const minutes = Number(match[1]);
      const seconds = Number(match[2]);
      const fraction = match[3] ? Number(match[3].padEnd(3, "0")) / 1000 : 0;
      const text = match[4].trim();
      return {
        timeSec: minutes * 60 + seconds + fraction,
        text,
      };
    })
    .filter((line): line is { timeSec: number; text: string } =>
      Boolean(line?.text)
    );
}

function findActiveLyricLine(
  lines: Array<{ timeSec: number; text: string }>,
  positionSec: number
): number {
  let activeIndex = -1;
  lines.forEach((line, index) => {
    if (line.timeSec <= positionSec) {
      activeIndex = index;
    }
  });
  return activeIndex;
}

function routeTitle(route: Route): string {
  switch (route) {
    case "home":
      return "首页";
    case "search":
      return "搜索";
    case "charts":
      return "排行榜";
    case "library":
      return "已下载";
    case "settings":
      return "设置";
    case "results":
      return "结果";
    case "player":
      return "播放";
    case "tasks":
      return "任务";
  }
}

function isMainRoute(route: Route): route is MainRoute {
  return ["home", "search", "charts", "library", "settings"].includes(route);
}

function formatClock(seconds: number): string {
  const safeSeconds = Math.max(0, Math.floor(seconds || 0));
  const minutes = Math.floor(safeSeconds / 60);
  const remainingSeconds = safeSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(
    remainingSeconds
  ).padStart(2, "0")}`;
}

function localizeRegion(id: string, label: string): string {
  const labels: Record<string, string> = {
    us: "美国",
    jp: "日本",
    hk: "香港",
    gb: "英国",
    tw: "台湾",
  };
  return labels[id] || label;
}

function formatNow(): string {
  return new Date().toISOString().replace("T", " ").slice(0, 19);
}

export default App;
