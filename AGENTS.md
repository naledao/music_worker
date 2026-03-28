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
