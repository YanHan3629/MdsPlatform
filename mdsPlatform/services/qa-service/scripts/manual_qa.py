"""多模态问答服务手动收发测试脚本。

用法示例：
    # 纯文本问答
    python scripts/manual_qa.py --question "用一句话介绍多模态大模型"

    # 图片 + 问题（可多张图片、多段文本）
    python scripts/manual_qa.py --question "这张图片里有什么？" --image C:/tmp/a.jpg
    python scripts/manual_qa.py --question "对比这两张图" --image a.jpg --image b.jpg

可选参数：
    --service-url   问答服务地址（默认 http://localhost:18081）
    --dataset-id / --version-id / --index-version-id
                    对齐全数据空间后端格式的占位 UUID（默认使用固定占位值）
    --max-tokens / --temperature  覆盖生成参数
    --json-only     只发文本（走 /api/v1/qa JSON 请求）
"""

import argparse
import json
import sys
import uuid
import urllib.error
import urllib.request
from pathlib import Path


DUMMY_DATASET_ID = "00000000-0000-0000-0000-000000000001"
DUMMY_VERSION_ID = "00000000-0000-0000-0000-000000000002"
DUMMY_INDEX_VERSION_ID = "00000000-0000-0000-0000-000000000003"


def build_multipart(fields: dict, files: list) -> tuple:
    """手工构建 multipart/form-data 请求体（不依赖 requests）。"""
    boundary = "----ManualQABoundary" + uuid.uuid4().hex
    parts = []
    for key, value in fields.items():
        if value is None:
            continue
        parts.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{key}"\r\n\r\n'
            f"{value}\r\n".encode("utf-8")
        )
    for key, filename, content_type, content in files:
        parts.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{key}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n".encode("utf-8")
            + content
            + b"\r\n"
        )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), boundary


def post_json(url: str, payload: dict) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_multipart(url: str, fields: dict, files: list) -> dict:
    body, boundary = build_multipart(fields, files)
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode("utf-8"))


def mime_of(path: Path) -> str:
    return {
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".png": "image/png",
        ".webp": "image/webp",
        ".bmp": "image/bmp",
        ".gif": "image/gif",
    }.get(path.suffix.lower(), "application/octet-stream")


def main() -> int:
    parser = argparse.ArgumentParser(description="多模态问答服务手动收发测试")
    parser.add_argument("--service-url", default="http://localhost:18081")
    parser.add_argument("--question", required=True, help="问题文本")
    parser.add_argument("--image", action="append", default=[], help="图片路径，可多次指定")
    parser.add_argument("--text", action="append", default=[], help="参考文本，可多次指定")
    parser.add_argument("--context", default=None, help="上下文提示")
    parser.add_argument("--dataset-id", default=DUMMY_DATASET_ID)
    parser.add_argument("--version-id", default=DUMMY_VERSION_ID)
    parser.add_argument("--index-version-id", default=DUMMY_INDEX_VERSION_ID)
    parser.add_argument("--max-tokens", type=int, default=None)
    parser.add_argument("--temperature", type=float, default=None)
    parser.add_argument("--json-only", action="store_true", help="只发文本，走 JSON 接口")
    args = parser.parse_args()

    base = args.service_url.rstrip("/")

    try:
        if args.json_only or not args.image:
            payload = {
                "datasetId": args.dataset_id,
                "versionId": args.version_id,
                "indexVersionId": args.index_version_id,
                "question": args.question,
                "texts": args.text or None,
                "context": args.context,
                "maxTokens": args.max_tokens,
                "temperature": args.temperature,
            }
            print(f">>> POST {base}/api/v1/qa (JSON)")
            resp = post_json(f"{base}/api/v1/qa", payload)
        else:
            fields = {
                "datasetId": args.dataset_id,
                "versionId": args.version_id,
                "indexVersionId": args.index_version_id,
                "question": args.question,
                "texts": json.dumps(args.text, ensure_ascii=False) if args.text else None,
                "context": args.context,
                "maxTokens": args.max_tokens,
                "temperature": args.temperature,
            }
            files = []
            for img_path in args.image:
                p = Path(img_path)
                if not p.is_file():
                    print(f"图片不存在: {img_path}", file=sys.stderr)
                    return 2
                files.append(("images", p.name, mime_of(p), p.read_bytes()))
            print(f">>> POST {base}/api/v1/qa（图片 {len(args.image)} 张）")
            resp = post_multipart(f"{base}/api/v1/qa", fields, files)

        print(json.dumps(resp, ensure_ascii=False, indent=2))
        return 0
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode('utf-8', errors='replace')}", file=sys.stderr)
        return 1
    except urllib.error.URLError as e:
        print(f"请求失败（请确认服务已启动）: {e.reason}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"请求失败: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
