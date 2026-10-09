"""Create a persistent release key once. Never replace an existing key."""
from pathlib import Path
import secrets
import subprocess
import os
ROOT=Path(__file__).resolve().parents[1]
KEY=ROOT/"signing/LocalTV-release.jks"
PROPERTIES=ROOT/"signing.properties"
if KEY.exists() or PROPERTIES.exists():
    if not (KEY.exists() and PROPERTIES.exists()):raise SystemExit("Key and properties must be preserved together")
    print("Existing signing identity preserved")
else:
    KEY.parent.mkdir(exist_ok=True)
    password=secrets.token_urlsafe(32)
    environment=os.environ.copy();environment["LOCALTV_SIGNING_PASSWORD"]=password
    subprocess.run(["keytool","-genkeypair","-keystore",str(KEY),"-storetype","JKS","-alias","localtv", "-keyalg","RSA","-keysize","3072","-validity","10000","-dname","CN=LocalTV, OU=Media Viewer, O=LocalTV, C=TW", "-storepass:env","LOCALTV_SIGNING_PASSWORD","-keypass:env","LOCALTV_SIGNING_PASSWORD"],env=environment,check=True,capture_output=True)
    PROPERTIES.write_text(f"storeFile=signing/LocalTV-release.jks\nstorePassword={password}\nkeyAlias=localtv\nkeyPassword={password}\n",encoding="utf-8")
    print("Release signing key created and stored locally")
