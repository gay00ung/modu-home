#!/usr/bin/env python3
"""Create an Android release identity outside the checkout without replacing keys."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys


def create_signing(directory: Path, keytool: str) -> dict:
    checkout = Path(__file__).resolve().parents[2]
    directory = directory.expanduser().resolve()
    if directory == checkout or checkout in directory.parents:
        raise ValueError("서명 키는 저장소 밖에 보관해주세요")
    if directory.exists():
        raise ValueError("기존 서명 폴더를 보존했어요 기존 keystore.properties를 사용해주세요")
    directory.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.mkdir(mode=0o700)
    password_file = directory / ".password"
    password = secrets.token_hex(32)
    alias = "modu-home-release"

    def private_write(path: Path, text: str):
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
            output.write(text)

    def run(*arguments):
        result = subprocess.run([keytool, *arguments], capture_output=True, check=False)
        if result.returncode:
            raise RuntimeError("keytool 실행에 실패했어요 서명 폴더를 확인해주세요")
        return result

    private_write(password_file, password + "\n")
    try:
        run("-genkeypair", "-keystore", str(directory / "modu-home-release.p12"),
            "-storetype", "PKCS12", "-storepass:file", str(password_file),
            "-alias", alias, "-keyalg", "RSA", "-keysize", "4096",
            "-sigalg", "SHA256withRSA", "-validity", "10000",
            "-dname", "CN=Modu Home", "-noprompt")
        os.chmod(directory / "modu-home-release.p12", 0o600)
        certificate = directory / "modu-home-release.cer"
        run("-exportcert", "-keystore", str(directory / "modu-home-release.p12"),
            "-storetype", "PKCS12", "-storepass:file", str(password_file),
            "-alias", alias, "-file", str(certificate))
        os.chmod(certificate, 0o600)
        private_write(directory / "keystore.properties",
                      "storeFile=modu-home-release.p12\n"
                      f"storePassword={password}\nkeyAlias={alias}\nkeyPassword={password}\n"
                      "storeType=PKCS12\n")
        metadata = {
            "applicationId": "io.moduhome.app",
            "certificateSha256": hashlib.sha256(certificate.read_bytes()).hexdigest(),
            "certificateSha1": hashlib.sha1(certificate.read_bytes()).hexdigest(),
            "keyAlgorithm": "RSA",
            "keySize": 4096,
            "validityDays": 10000,
        }
        private_write(directory / "certificate.json", json.dumps(metadata, indent=2) + "\n")
        return metadata
    finally:
        password_file.unlink(missing_ok=True)


def main():
    default = Path.home() / "Library/Application Support/modu-home/signing" if sys.platform == "darwin" \
        else Path.home() / ".local/share/modu-home/signing"
    parser = argparse.ArgumentParser(description="새 배포 서명 키와 외부 설정 파일 생성")
    parser.add_argument("--directory", type=Path, default=default)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    keytool = str(java_home / "bin/keytool") if java_home else shutil.which("keytool")
    if not keytool or not Path(keytool).is_file():
        parser.error("JDK의 keytool을 찾지 못했어요 --java-home으로 JDK를 지정해주세요")
    try:
        print(json.dumps(create_signing(args.directory, keytool), ensure_ascii=False, indent=2))
    except (ValueError, OSError, RuntimeError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
