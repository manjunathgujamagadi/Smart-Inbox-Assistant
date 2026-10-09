#!/usr/bin/env python3
"""Deliver the synthetic test emails (emails/*.json + their PDFs) into the real test mailbox.

This turns the fixture set into actual email, so the app ingests it the way the assignment
describes: IMAP intake from a real mailbox, not a JSON loader. Only the standard library is used.

Two delivery modes:

  smtp (default)  Really sends each email over SMTP to the test mailbox. Most realistic, but the
                  provider rewrites the From *address* to the sending account, so the synthetic
                  reporter is kept as the display name and in Reply-To.
  append          Drops each email straight into the mailbox over IMAP APPEND, keeping the
                  synthetic From address exactly as written in the fixture. Use this when the
                  sender identity matters more than going through SMTP.

Credentials come from the repo-root .env (or the environment):
  MAIL_USERNAME / MAIL_PASSWORD          the test mailbox (Gmail: an app password)
  MAIL_IMAP_HOST / MAIL_IMAP_PORT        for --mode append (default imap.gmail.com:993)
  SMTP_HOST / SMTP_PORT                  for --mode smtp (default smtp.gmail.com:465)
  SMTP_USERNAME / SMTP_PASSWORD          sending account (default: the test mailbox itself)
  MAIL_TO                                recipient (default: MAIL_USERNAME)

Examples:
  python3 send_test_emails.py --dry-run
  python3 send_test_emails.py                     # all 18 fixtures
  python3 send_test_emails.py --only icsr-01 pqc-01
  python3 send_test_emails.py --mode append

All content is synthetic. Never point this at a mailbox that receives real patient data.
"""

from __future__ import annotations

import argparse
import imaplib
import json
import os
import re
import smtplib
import ssl
import sys
import time
from email.message import EmailMessage
from email.utils import formataddr, formatdate, make_msgid, parseaddr
from pathlib import Path

HERE = Path(__file__).resolve().parent
EMAILS_DIR = HERE / "emails"


def load_dotenv(path: Path) -> None:
    """Minimal .env reader: KEY=VALUE lines; real environment variables win."""
    if not path.is_file():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def display_name(address: str) -> str:
    """'dr.priya.nair@rivermed-clinic.example' -> 'Dr. Priya Nair'."""
    name, addr = parseaddr(address)
    if name:
        return name
    words = [w for w in re.split(r"[._\-+]+", addr.split("@")[0]) if w]
    return " ".join("Dr." if w.lower() == "dr" else w.capitalize() for w in words)


def build_email(fixture_path: Path, from_addr: str, to_addr: str, keep_original_from: bool) -> EmailMessage:
    fixture = json.loads(fixture_path.read_text())
    original_sender = fixture["sender"]

    msg = EmailMessage()
    if keep_original_from:
        msg["From"] = original_sender
    else:
        msg["From"] = formataddr((display_name(original_sender), from_addr))
        msg["Reply-To"] = original_sender
    msg["To"] = to_addr
    msg["Subject"] = fixture["subject"]
    msg["Date"] = formatdate(localtime=True)
    msg["Message-ID"] = make_msgid(idstring=fixture_path.stem, domain="smart-inbox-test.example")
    msg["X-Synthetic-Test-Data"] = "true"
    msg["X-Test-Fixture"] = fixture_path.name
    msg.set_content(fixture["bodyText"])

    for rel in fixture.get("attachments") or []:
        att = (fixture_path.parent / rel).resolve()
        data = att.read_bytes()
        if att.suffix.lower() == ".pdf":
            msg.add_attachment(data, maintype="application", subtype="pdf", filename=att.name)
        else:
            msg.add_attachment(data, maintype="application", subtype="octet-stream", filename=att.name)
    return msg


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=["smtp", "append"], default="smtp")
    parser.add_argument("--only", nargs="*", help="fixture names (without .json) to send; default all")
    parser.add_argument("--delay", type=float, default=2.0, help="seconds between emails (avoid rate limits)")
    parser.add_argument("--dry-run", action="store_true", help="list what would be delivered, send nothing")
    args = parser.parse_args()

    load_dotenv(HERE.parent / ".env")
    mailbox = os.environ.get("MAIL_USERNAME", "")
    mailbox_pw = os.environ.get("MAIL_PASSWORD", "")
    to_addr = os.environ.get("MAIL_TO") or mailbox

    fixtures = sorted(EMAILS_DIR.glob("*.json"))
    if args.only:
        wanted = set(args.only)
        fixtures = [f for f in fixtures if f.stem in wanted]
        missing = wanted - {f.stem for f in fixtures}
        if missing:
            print(f"Unknown fixture(s): {', '.join(sorted(missing))}", file=sys.stderr)
            return 2
    if not fixtures:
        print("No fixtures to send.", file=sys.stderr)
        return 2

    if args.dry_run:
        for f in fixtures:
            fx = json.loads(f.read_text())
            atts = ", ".join(Path(a).name for a in fx.get("attachments") or []) or "no attachments"
            print(f"{f.stem:20} {fx['subject'][:60]:60} [{atts}]")
        print(f"\n{len(fixtures)} email(s) would be delivered to {to_addr or '(MAIL_TO / MAIL_USERNAME not set)'} via {args.mode}")
        return 0

    if not mailbox or not mailbox_pw:
        print("Set MAIL_USERNAME and MAIL_PASSWORD in the repo-root .env first.", file=sys.stderr)
        return 2

    ctx = ssl.create_default_context()
    if args.mode == "smtp":
        host = os.environ.get("SMTP_HOST", "smtp.gmail.com")
        port = int(os.environ.get("SMTP_PORT", "465"))
        user = os.environ.get("SMTP_USERNAME") or mailbox
        password = os.environ.get("SMTP_PASSWORD") or mailbox_pw
        with smtplib.SMTP_SSL(host, port, context=ctx, timeout=60) as smtp:
            smtp.login(user, password)
            for i, f in enumerate(fixtures, 1):
                smtp.send_message(build_email(f, user, to_addr, keep_original_from=False))
                print(f"[{i}/{len(fixtures)}] sent {f.stem}")
                if i < len(fixtures):
                    time.sleep(args.delay)
    else:
        host = os.environ.get("MAIL_IMAP_HOST", "imap.gmail.com")
        port = int(os.environ.get("MAIL_IMAP_PORT", "993"))
        folder = os.environ.get("MAIL_INBOX_FOLDER", "INBOX")
        with imaplib.IMAP4_SSL(host, port, ssl_context=ctx) as imap:
            imap.login(mailbox, mailbox_pw)
            for i, f in enumerate(fixtures, 1):
                msg = build_email(f, mailbox, to_addr, keep_original_from=True)
                status, detail = imap.append(folder, "", imaplib.Time2Internaldate(time.time()), msg.as_bytes())
                if status != "OK":
                    print(f"[{i}/{len(fixtures)}] FAILED {f.stem}: {detail}", file=sys.stderr)
                    return 1
                print(f"[{i}/{len(fixtures)}] appended {f.stem}")

    print(f"\nDelivered {len(fixtures)} email(s) to {to_addr}. "
          "The backend picks them up on its next poll, or click 'Check mailbox' in the review UI.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
