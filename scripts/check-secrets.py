"""Fail on credential-shaped values; reports paths only, never the values."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
patterns = [rb"AIza[0-9A-Za-z_-]{30,}", rb"GOCSPX-[0-9A-Za-z_-]+", rb"ya29\.[0-9A-Za-z_-]+",
            rb"gh[pousr]_[0-9A-Za-z]{25,}", rb"AKIA[0-9A-Z]{16}",
            rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"]
failures = []
for path in root.rglob("*"):
    if not path.is_file() or any(part in {".git", "build", ".gradle", ".idea"} for part in path.relative_to(root).parts):
        continue
    if path.suffix.lower() in {".jks", ".keystore", ".p12", ".key"}:
        failures.append(str(path.relative_to(root)))
    elif path.suffix not in {".png", ".jar"} and any(re.search(pattern, path.read_bytes()) for pattern in patterns):
        failures.append(str(path.relative_to(root)))
if failures:
    print("Possible credentials in: " + ", ".join(failures))
    sys.exit(1)
print("Credential-pattern scan passed.")
