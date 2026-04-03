import json
import os
import threading
import time
import urllib.parse
import urllib.request
from typing import Any

from music_config import STATE_DIR, YTDLP_PROXY


APPLE_MUSIC_CHART_REGIONS = [
    {"id": "us", "label": "美国"},
    {"id": "jp", "label": "日本"},
    {"id": "gb", "label": "英国"},
    {"id": "kr", "label": "韩国"},
    {"id": "cn", "label": "中国"},
]
APPLE_MUSIC_CHART_REGION_IDS = {item["id"] for item in APPLE_MUSIC_CHART_REGIONS}
APPLE_MUSIC_CHART_REGION_LABELS = {item["id"]: item["label"] for item in APPLE_MUSIC_CHART_REGIONS}
APPLE_MUSIC_CHART_MAX_LIMIT = 100
APPLE_MUSIC_CHART_CACHE_TTL_SEC = 15 * 60
APPLE_MUSIC_CHART_TIMEOUT_SEC = 20
APPLE_MUSIC_CHART_FETCH_RETRIES = 2
CHARTS_CACHE_DIR = os.path.join(STATE_DIR, "charts_cache")

os.makedirs(CHARTS_CACHE_DIR, exist_ok=True)


def iso_ts(ts: float | None = None) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts or time.time()))


class ChartFetchError(RuntimeError):
    pass


class ChartsService:
    def __init__(self) -> None:
        self._lock = threading.Lock()

    def get_sources_payload(self) -> dict[str, Any]:
        return {
            "sources": [
                {
                    "id": "apple_music",
                    "label": "Apple Music",
                    "types": ["songs"],
                    "periods": ["daily"],
                    "regions": APPLE_MUSIC_CHART_REGIONS,
                }
            ]
        }

    def get_chart_payload(
        self,
        source: str,
        chart_type: str,
        period: str,
        region: str,
        limit: int,
        force_refresh: bool = False,
    ) -> dict[str, Any]:
        normalized_source = (source or "").strip().lower()
        normalized_type = (chart_type or "").strip().lower()
        normalized_period = (period or "").strip().lower()
        normalized_region = (region or "").strip().lower()

        if normalized_source != "apple_music":
            raise ValueError(f"unsupported chart source: {source}")
        if normalized_type != "songs":
            raise ValueError(f"unsupported chart type: {chart_type}")
        if normalized_period != "daily":
            raise ValueError(f"unsupported chart period: {period}")
        if normalized_region not in APPLE_MUSIC_CHART_REGION_IDS:
            raise ValueError(f"unsupported chart region: {region}")

        normalized_limit = max(1, min(APPLE_MUSIC_CHART_MAX_LIMIT, int(limit or 50)))
        cache_key = f"{normalized_source}_{normalized_type}_{normalized_period}_{normalized_region}_{normalized_limit}"

        fresh_cached_payload = self._load_cache(cache_key)
        if not force_refresh:
            cached_payload = fresh_cached_payload
            if cached_payload is not None:
                payload = dict(cached_payload)
                payload["title"] = build_apple_music_chart_title(normalized_region)
                payload["fromCache"] = True
                return payload

        try:
            payload = self._fetch_apple_music_daily_songs(normalized_region, normalized_limit)
            payload["title"] = build_apple_music_chart_title(normalized_region)
            self._save_cache(cache_key, payload)
            payload["fromCache"] = False
            return payload
        except ChartFetchError:
            fallback_payload = fresh_cached_payload or self._load_cache(cache_key, allow_stale=True)
            if fallback_payload is None:
                raise
            payload = dict(fallback_payload)
            payload["title"] = build_apple_music_chart_title(normalized_region)
            payload["fromCache"] = True
            return payload

    def _cache_file_path(self, cache_key: str) -> str:
        return os.path.join(CHARTS_CACHE_DIR, f"{cache_key}.json")

    def _load_cache(self, cache_key: str, allow_stale: bool = False) -> dict[str, Any] | None:
        cache_file = self._cache_file_path(cache_key)
        if not os.path.exists(cache_file):
            return None
        try:
            with open(cache_file, "r", encoding="utf-8") as f:
                cached = json.load(f)
            fetched_at = float(cached.get("fetchedAt") or 0)
            is_stale = fetched_at <= 0 or time.time() - fetched_at > APPLE_MUSIC_CHART_CACHE_TTL_SEC
            if is_stale and not allow_stale:
                return None
            payload = cached.get("payload")
            if isinstance(payload, dict):
                return payload
        except Exception:
            return None
        return None

    def _save_cache(self, cache_key: str, payload: dict[str, Any]) -> None:
        cache_file = self._cache_file_path(cache_key)
        cache_body = {
            "fetchedAt": time.time(),
            "payload": payload,
        }
        temp_file = f"{cache_file}.tmp"
        with self._lock:
            with open(temp_file, "w", encoding="utf-8") as f:
                json.dump(cache_body, f, ensure_ascii=False)
            os.replace(temp_file, cache_file)

    def _fetch_apple_music_daily_songs(self, region: str, limit: int) -> dict[str, Any]:
        url = f"https://rss.applemarketingtools.com/api/v2/{region}/music/most-played/{limit}/songs.json"
        request = urllib.request.Request(
            url,
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept": "application/json",
            },
        )

        errors: list[str] = []
        payload: dict[str, Any] | None = None
        for retry_index in range(APPLE_MUSIC_CHART_FETCH_RETRIES):
            for attempt_label, opener in self._build_url_openers():
                try:
                    with opener.open(request, timeout=APPLE_MUSIC_CHART_TIMEOUT_SEC) as resp:
                        payload = json.load(resp)
                    break
                except Exception as e:
                    errors.append(f"{attempt_label}[{retry_index + 1}/{APPLE_MUSIC_CHART_FETCH_RETRIES}]: {e}")
                    continue
            if payload is not None:
                break
            if retry_index < APPLE_MUSIC_CHART_FETCH_RETRIES - 1:
                time.sleep(0.8)

        if payload is None:
            raise ChartFetchError("apple music chart request failed: " + " | ".join(errors))

        feed = payload.get("feed") if isinstance(payload, dict) else None
        if not isinstance(feed, dict):
            raise ChartFetchError("apple music chart payload missing feed")

        results = feed.get("results")
        if not isinstance(results, list):
            raise ChartFetchError("apple music chart payload missing results")

        items: list[dict[str, Any]] = []
        for index, item in enumerate(results, start=1):
            if not isinstance(item, dict):
                continue
            title = str(item.get("name") or "").strip()
            artist = str(item.get("artistName") or "").strip()
            if not title or not artist:
                continue
            items.append(
                {
                    "rank": index,
                    "title": title,
                    "artist": artist,
                    "cover": normalize_apple_artwork_url(str(item.get("artworkUrl100") or "").strip() or None),
                    "album": None,
                    "durationSec": None,
                    "deeplink": str(item.get("url") or "").strip() or None,
                    "searchKeyword": f"{title} {artist}".strip(),
                    "sourceId": str(item.get("id") or "").strip() or None,
                    "releaseDate": str(item.get("releaseDate") or "").strip() or None,
                }
            )

        return {
            "source": "apple_music",
            "type": "songs",
            "period": "daily",
            "region": region,
            "title": build_apple_music_chart_title(region),
            "updatedAt": iso_ts(),
            "items": items,
        }

    def _build_url_openers(self) -> list[tuple[str, urllib.request.OpenerDirector]]:
        openers: list[tuple[str, urllib.request.OpenerDirector]] = [("direct", urllib.request.build_opener())]
        proxy = (YTDLP_PROXY or "").strip()
        if proxy:
            parsed = urllib.parse.urlsplit(proxy)
            if parsed.scheme and parsed.netloc:
                openers.append(
                    (
                        "proxy",
                        urllib.request.build_opener(
                            urllib.request.ProxyHandler(
                                {
                                    "http": proxy,
                                    "https": proxy,
                                }
                            )
                        ),
                    )
                )
        return openers


def build_apple_music_chart_title(region: str) -> str:
    normalized_region = (region or "").strip().lower()
    region_label = APPLE_MUSIC_CHART_REGION_LABELS.get(normalized_region, normalized_region.upper())
    return f"Apple Music 热门歌曲（{region_label}）"


def normalize_apple_artwork_url(url: str | None) -> str | None:
    if not url:
        return None
    normalized = (url or "").strip()
    if not normalized:
        return None
    return normalized.replace("100x100bb.jpg", "600x600bb.jpg")
