import base64
import hashlib
import hmac
import getpass

region = "eu-central-1"
secret_key = getpass.getpass("AWS Secret Access Key: ")

def sign(key, message):
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()

key = ("AWS4" + secret_key).encode("utf-8")
key = sign(key, "11111111")
key = sign(key, region)
key = sign(key, "ses")
key = sign(key, "aws4_request")
signature = hmac.new(key, b"SendRawEmail", hashlib.sha256).digest()

smtp_password = base64.b64encode(b"\x04" + signature).decode("utf-8")

print("\nSES SMTP-Passwort:")
print(smtp_password)