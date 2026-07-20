export function formatBytes(bytes?: number | null): string {
  const safeBytes = bytes ?? 0;
  if (safeBytes <= 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = safeBytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return index === 0 ? `${Math.round(value)} ${units[index]}` : `${value.toFixed(1)} ${units[index]}`;
}

export function formatDuration(durationSec?: number | null): string {
  const totalSeconds = Math.max(Math.round(durationSec ?? 0), 0);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`;
}

export function formatPosition(positionMs: number): string {
  const totalSeconds = Math.max(Math.floor(positionMs / 1000), 0);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

export function formatSpeed(speedBps?: number | null): string {
  const speed = speedBps ?? 0;
  const kbps = speed / 1024;
  const mbps = kbps / 1024;
  return mbps >= 1 ? `${mbps.toFixed(2)} MB/s` : `${Math.round(kbps)} KB/s`;
}

export function formatTimestamp(timestamp?: string | null): string {
  if (!timestamp) {
    return '';
  }
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) {
    return timestamp;
  }
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hour = String(date.getHours()).padStart(2, '0');
  const minute = String(date.getMinutes()).padStart(2, '0');
  return `${year}-${month}-${day} ${hour}:${minute}`;
}

export function formatTaskStatus(status?: string | null): string {
  switch (status) {
    case 'queued':
      return '排队中';
    case 'running':
      return '进行中';
    case 'finished':
      return '已完成';
    case 'failed':
      return '失败';
    default:
      return status?.trim() || '未知';
  }
}

export function formatTaskStage(stage?: string | null): string {
  switch (stage) {
    case 'queued':
      return '排队中';
    case 'starting':
      return '开始处理';
    case 'searching':
      return '搜索资源';
    case 'downloading':
      return '下载音频';
    case 'preparing':
      return '准备环境';
    case 'uploading_audio':
      return '上传音频';
    case 'transcribing':
      return '生成歌词';
    case 'pulling_lrc':
      return '回传 LRC';
    case 'embedding_lyrics':
      return '写入 MP3';
    case 'already_exists':
      return '歌词已存在';
    case 'completed':
    case 'finished':
      return '已完成';
    case 'failed':
      return '失败';
    default:
      return stage?.trim() || '处理中';
  }
}

export function compactTitle(value?: string | null, fallback = '未命名歌曲'): string {
  const trimmed = value?.trim();
  return trimmed || fallback;
}
