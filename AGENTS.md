# AGENTS

## Temporary GitHub Proxy Rule

- All GitHub interactions must temporarily go through the local proxy on `127.0.0.1:7890`.
- This applies to `git` operations against GitHub and to `gh`, `curl`, `wget`, or any other GitHub HTTP requests.
- Before running GitHub-related commands, export:

```bash
export HTTP_PROXY=http://127.0.0.1:7890
export HTTPS_PROXY=http://127.0.0.1:7890
export ALL_PROXY=socks5://127.0.0.1:7890
```

- If a command only supports one proxy variable, prefer `HTTPS_PROXY` first.
- This is a temporary operational rule and should be followed until explicitly removed.

## APK Version Bump Rule

- Every time an APK is packaged, the version must advance from the most recently packaged APK version.
- `versionCode` must increase by `1` on every APK packaging.
- `versionName` must use a three-part decimal format such as `1.0.9`.
- `versionName` must advance by `1` each time, carrying at `10`.
- Carrying rule:
  - `1.0.8 -> 1.0.9`
  - `1.0.9 -> 1.1.0`
  - `1.9.9 -> 2.0.0`
- This rule applies to both debug/release packaging whenever the APK version is updated for a new build.
