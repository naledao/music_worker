import datetime as dt
import hashlib
import hmac
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


DEFAULT_ENV_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run", "minio.env")
DEFAULT_REGION = "us-east-1"
DEFAULT_BUCKET = "music-worker"
DEFAULT_ANDROID_PREFIX = "android/releases"
ANDROID_APK_CONTENT_TYPE = "application/vnd.android.package-archive"
ANDROID_MANIFEST_CONTENT_TYPE = "application/json; charset=utf-8"

_NO_PROXY_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


@dataclass(frozen=True)
class MinioConfig:
    endpoint: str
    public_endpoint: str
    access_key: str
    secret_key: str
    bucket: str
    android_prefix: str
    region: str = DEFAULT_REGION


class MinioConfigError(RuntimeError):
    pass


class MinioRequestError(RuntimeError):
    def __init__(self, method: str, url: str, status: int, body: str):
        super().__init__(f"MinIO {method} {url} failed: HTTP {status} {body}".strip())
        self.method = method
        self.url = url
        self.status = status
        self.body = body


def load_env_file(file_path: str = DEFAULT_ENV_FILE) -> dict[str, str]:
    values: dict[str, str] = {}
    if not os.path.exists(file_path):
        return values

    with open(file_path, "r", encoding="utf-8") as f:
        for raw_line in f:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            key = key.strip()
            value = value.strip().strip('"').strip("'")
            if key:
                values[key] = value
    return values


def _get_config_value(name: str, env_file_values: dict[str, str], default: str = "") -> str:
    return (os.environ.get(name) or env_file_values.get(name) or default).strip()


def get_minio_config(required: bool = True) -> MinioConfig | None:
    env_file_values = load_env_file()
    endpoint = _get_config_value("MUSIC_MINIO_ENDPOINT", env_file_values)
    access_key = (
        _get_config_value("MUSIC_MINIO_ACCESS_KEY", env_file_values)
        or _get_config_value("MUSIC_MINIO_USERNAME", env_file_values)
    )
    secret_key = (
        _get_config_value("MUSIC_MINIO_SECRET_KEY", env_file_values)
        or _get_config_value("MUSIC_MINIO_PASSWORD", env_file_values)
    )

    if not endpoint or not access_key or not secret_key:
        if required:
            raise MinioConfigError(
                "MinIO config is incomplete; set MUSIC_MINIO_ENDPOINT, "
                "MUSIC_MINIO_ACCESS_KEY and MUSIC_MINIO_SECRET_KEY"
            )
        return None

    public_endpoint = _get_config_value("MUSIC_MINIO_PUBLIC_ENDPOINT", env_file_values, endpoint) or endpoint
    bucket = _get_config_value("MUSIC_MINIO_BUCKET", env_file_values, DEFAULT_BUCKET) or DEFAULT_BUCKET
    android_prefix = (
        _get_config_value("MUSIC_MINIO_ANDROID_PREFIX", env_file_values, DEFAULT_ANDROID_PREFIX)
        or DEFAULT_ANDROID_PREFIX
    )
    region = _get_config_value("MUSIC_MINIO_REGION", env_file_values, DEFAULT_REGION) or DEFAULT_REGION

    return MinioConfig(
        endpoint=endpoint.rstrip("/"),
        public_endpoint=public_endpoint.rstrip("/"),
        access_key=access_key,
        secret_key=secret_key,
        bucket=bucket.strip("/"),
        android_prefix=android_prefix.strip("/"),
        region=region,
    )


def android_latest_manifest_key(config: MinioConfig) -> str:
    return f"{config.android_prefix}/latest.json"


def android_release_apk_key(config: MinioConfig, version_name: str | None, version_code: int | str | None, file_name: str) -> str:
    version_segment = str(version_name or "unknown").strip() or "unknown"
    code_segment = str(version_code or "0").strip() or "0"
    safe_file_name = os.path.basename(file_name).replace("\\", "_").replace("/", "_")
    return f"{config.android_prefix}/{version_segment}-{code_segment}/{safe_file_name}"


def sha256_file(file_path: str) -> str:
    digest = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso_ts(ts: dt.datetime | None = None) -> str:
    return (ts or utc_now()).strftime("%Y-%m-%dT%H:%M:%SZ")


def _sign(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def _signature_key(secret_key: str, date_stamp: str, region: str) -> bytes:
    key_date = _sign(("AWS4" + secret_key).encode("utf-8"), date_stamp)
    key_region = _sign(key_date, region)
    key_service = _sign(key_region, "s3")
    return _sign(key_service, "aws4_request")


def _canonical_uri(path: str) -> str:
    return "/" + "/".join(urllib.parse.quote(part, safe="~") for part in path.strip("/").split("/"))


def _canonical_query(params: dict[str, str]) -> str:
    encoded = [
        (
            urllib.parse.quote(str(key), safe="-_.~"),
            urllib.parse.quote(str(value), safe="-_.~"),
        )
        for key, value in params.items()
    ]
    encoded.sort()
    return "&".join(f"{key}={value}" for key, value in encoded)


def _object_url(endpoint: str, bucket: str, key: str = "") -> str:
    if key:
        return f"{endpoint}/{urllib.parse.quote(bucket, safe='')}/{urllib.parse.quote(key, safe='/~')}"
    return f"{endpoint}/{urllib.parse.quote(bucket, safe='')}"


def _signed_request(
    config: MinioConfig,
    method: str,
    url: str,
    body: bytes = b"",
    headers: dict[str, str] | None = None,
) -> tuple[urllib.request.Request, str]:
    headers = dict(headers or {})
    parsed = urllib.parse.urlsplit(url)
    host = parsed.netloc
    now = utc_now()
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()

    signed_headers_map = {
        "host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
    }
    for key, value in headers.items():
        signed_headers_map[key.lower()] = str(value).strip()
    canonical_headers = "".join(f"{key}:{signed_headers_map[key]}\n" for key in sorted(signed_headers_map))
    signed_headers = ";".join(sorted(signed_headers_map))
    canonical_request = "\n".join(
        [
            method.upper(),
            _canonical_uri(parsed.path),
            parsed.query,
            canonical_headers,
            signed_headers,
            payload_hash,
        ]
    )
    credential_scope = f"{date_stamp}/{config.region}/s3/aws4_request"
    string_to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            amz_date,
            credential_scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        ]
    )
    signature = hmac.new(
        _signature_key(config.secret_key, date_stamp, config.region),
        string_to_sign.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    request_headers = dict(headers)
    request_headers["Host"] = host
    request_headers["X-Amz-Date"] = amz_date
    request_headers["X-Amz-Content-Sha256"] = payload_hash
    request_headers["Authorization"] = (
        "AWS4-HMAC-SHA256 "
        f"Credential={config.access_key}/{credential_scope}, "
        f"SignedHeaders={signed_headers}, "
        f"Signature={signature}"
    )
    return urllib.request.Request(url, data=body if method.upper() != "HEAD" else None, method=method.upper(), headers=request_headers), payload_hash


def minio_request(
    config: MinioConfig,
    method: str,
    url: str,
    body: bytes = b"",
    headers: dict[str, str] | None = None,
    expected_statuses: tuple[int, ...] = (200,),
) -> bytes:
    request, _ = _signed_request(config, method, url, body=body, headers=headers)
    try:
        with _NO_PROXY_OPENER.open(request, timeout=30) as response:
            response_body = response.read()
            if response.status not in expected_statuses:
                raise MinioRequestError(method, url, response.status, response_body.decode("utf-8", errors="ignore"))
            return response_body
    except urllib.error.HTTPError as error:
        error_body = error.read().decode("utf-8", errors="ignore")
        if error.code in expected_statuses:
            return error_body.encode("utf-8")
        raise MinioRequestError(method, url, error.code, error_body) from error


def ensure_bucket(config: MinioConfig) -> None:
    bucket_url = _object_url(config.endpoint, config.bucket)
    try:
        minio_request(config, "HEAD", bucket_url, expected_statuses=(200,))
        return
    except MinioRequestError as error:
        if error.status not in (404,):
            raise
    minio_request(config, "PUT", bucket_url, expected_statuses=(200,))


def put_object(config: MinioConfig, key: str, body: bytes, content_type: str) -> None:
    minio_request(
        config,
        "PUT",
        _object_url(config.endpoint, config.bucket, key),
        body=body,
        headers={
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
        expected_statuses=(200,),
    )


def get_object(config: MinioConfig, key: str) -> bytes:
    return minio_request(config, "GET", _object_url(config.endpoint, config.bucket, key), expected_statuses=(200,))


def get_json_object(config: MinioConfig, key: str) -> dict[str, Any]:
    raw = get_object(config, key)
    payload = json.loads(raw.decode("utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"MinIO object is not a JSON object: {key}")
    return payload


def presigned_get_url(config: MinioConfig, key: str, expires: int = 3600) -> str:
    expires = max(60, min(int(expires), 7 * 24 * 60 * 60))
    now = utc_now()
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    credential_scope = f"{date_stamp}/{config.region}/s3/aws4_request"
    parsed = urllib.parse.urlsplit(_object_url(config.public_endpoint, config.bucket, key))
    query_params = {
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": f"{config.access_key}/{credential_scope}",
        "X-Amz-Date": amz_date,
        "X-Amz-Expires": str(expires),
        "X-Amz-SignedHeaders": "host",
    }
    canonical_query = _canonical_query(query_params)
    canonical_request = "\n".join(
        [
            "GET",
            _canonical_uri(parsed.path),
            canonical_query,
            f"host:{parsed.netloc}\n",
            "host",
            "UNSIGNED-PAYLOAD",
        ]
    )
    string_to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            amz_date,
            credential_scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        ]
    )
    signature = hmac.new(
        _signature_key(config.secret_key, date_stamp, config.region),
        string_to_sign.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return urllib.parse.urlunsplit(
        (
            parsed.scheme,
            parsed.netloc,
            parsed.path,
            f"{canonical_query}&X-Amz-Signature={signature}",
            "",
        )
    )


def build_android_release_manifest(
    config: MinioConfig,
    apk_path: str,
    apk_key: str,
    version_code: int | None,
    version_name: str | None,
    file_name: str | None = None,
) -> dict[str, Any]:
    file_name = file_name or os.path.basename(apk_path)
    return {
        "schemaVersion": 1,
        "platform": "android",
        "versionCode": version_code,
        "versionName": version_name,
        "fileName": file_name,
        "fileSize": os.path.getsize(apk_path),
        "sha256": sha256_file(apk_path),
        "updatedAt": iso_ts(dt.datetime.fromtimestamp(os.path.getmtime(apk_path), tz=dt.timezone.utc)),
        "uploadedAt": iso_ts(),
        "bucket": config.bucket,
        "objectKey": apk_key,
        "contentType": ANDROID_APK_CONTENT_TYPE,
    }


def publish_android_release(
    apk_path: str,
    version_code: int | None,
    version_name: str | None,
    file_name: str | None = None,
) -> dict[str, Any]:
    config = get_minio_config(required=True)
    assert config is not None
    ensure_bucket(config)
    upload_file_name = file_name or os.path.basename(apk_path)
    apk_key = android_release_apk_key(config, version_name, version_code, upload_file_name)

    with open(apk_path, "rb") as f:
        put_object(config, apk_key, f.read(), ANDROID_APK_CONTENT_TYPE)

    manifest = build_android_release_manifest(
        config=config,
        apk_path=apk_path,
        apk_key=apk_key,
        version_code=version_code,
        version_name=version_name,
        file_name=upload_file_name,
    )
    manifest_body = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    put_object(config, android_latest_manifest_key(config), manifest_body, ANDROID_MANIFEST_CONTENT_TYPE)
    return manifest


def get_android_update_payload_from_minio(expires: int = 3600) -> dict[str, Any] | None:
    config = get_minio_config(required=False)
    if config is None:
        return None
    manifest = get_json_object(config, android_latest_manifest_key(config))
    object_key = str(manifest.get("objectKey") or "").strip()
    if not object_key:
        raise ValueError("MinIO android update manifest missing objectKey")
    return {
        "versionCode": manifest.get("versionCode"),
        "versionName": manifest.get("versionName"),
        "fileName": manifest.get("fileName") or os.path.basename(object_key),
        "fileSize": manifest.get("fileSize") or 0,
        "sha256": manifest.get("sha256"),
        "updatedAt": manifest.get("updatedAt") or manifest.get("uploadedAt"),
        "downloadPath": presigned_get_url(config, object_key, expires=expires),
        "source": "minio",
        "bucket": manifest.get("bucket") or config.bucket,
        "objectKey": object_key,
        "urlExpiresInSec": expires,
    }
