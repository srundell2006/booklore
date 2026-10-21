#!/usr/bin/env python3
"""
Tests for server.py. Stdlib only, no Calibre and no network needed:

    python3 -I docker/ebook-converter/test_server.py
"""
import importlib.util
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from urllib.parse import unquote

# Load server.py by path, without writing __pycache__ next to it
sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "server", os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.py"))
server = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(server)

BOUNDARY = "----BookLoreTest"


def multipart(disposition: str, payload: bytes = b"MOBI", target: str = "epub") -> bytes:
    """A body laid out the way EbookConversionService writes it."""
    return (f"--{BOUNDARY}\r\nContent-Disposition: {disposition}\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode() + payload + (
        f"\r\n--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"target\"\r\n\r\n"
        f"{target}\r\n--{BOUNDARY}--\r\n").encode()


def file_part(filename: str) -> str:
    return f'form-data; name="file"; filename="{filename}"'


class ParseMultipartTest(unittest.TestCase):

    def parse(self, disposition: str):
        return server.parse_multipart(multipart(disposition), f"multipart/form-data; boundary={BOUNDARY}")

    def test_file_names_keep_semicolons_and_any_script(self):
        for filename in [
            "Pride and Prejudice; or, First Impressions - Jane Austen.mobi",
            "Война и мир; книга 1 - Лев Толстой.mobi",
            "قصر الشوق - نجيب محفوظ.azw3",
            "it's a=b; c.mobi",
        ]:
            with self.subTest(filename=filename):
                files, fields = self.parse(file_part(filename))
                self.assertEqual(files, {"file": (filename, b"MOBI")})
                self.assertEqual(fields, {"target": "epub"})

    def test_unquoted_and_rfc5987_file_names_are_read(self):
        self.assertEqual(self.parse('form-data; name=file; filename=plain.mobi')[0],
                         {"file": ("plain.mobi", b"MOBI")})
        self.assertEqual(self.parse("form-data; name=\"file\"; filename*=UTF-8''%D9%82%D8%B5%D8%B1.mobi")[0],
                         {"file": ("قصر.mobi", b"MOBI")})

    def test_part_without_file_name_is_a_field(self):
        files, fields = self.parse('form-data; name="file"')
        self.assertEqual(files, {})
        self.assertEqual(fields, {"file": "MOBI", "target": "epub"})


class ContentDispositionTest(unittest.TestCase):

    def test_header_is_latin1_with_the_exact_name_in_filename_star(self):
        for filename in ["Война и мир.epub", "中国文化合集.epub", "Philosopher’s Stone; Part 1.epub", 'a"b\\c.epub']:
            with self.subTest(filename=filename):
                header = server.content_disposition(filename)
                header.encode("latin-1")
                encoded = re.search(r"filename\*=UTF-8''(\S+)$", header).group(1)
                self.assertEqual(unquote(encoded, encoding="utf-8"), filename)


class ConvertTest(unittest.TestCase):
    """Runs the real handler over a socket pair, with ebook-convert stubbed out."""

    def setUp(self):
        work_dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, work_dir, True)
        self.converted = []

        def fake_ebook_convert(args, **kwargs):
            self.converted.append(os.path.basename(args[1]))
            with open(args[2], "wb") as handle:
                handle.write(b"EPUB")
            return subprocess.CompletedProcess(args, 0, "", "")

        for patcher in (mock.patch.object(server, "WORK_DIR", work_dir),
                        mock.patch.object(server.subprocess, "run", side_effect=fake_ebook_convert),
                        mock.patch.object(server.log, "disabled", True)):
            patcher.start()
            self.addCleanup(patcher.stop)

    def post(self, body: bytes):
        client, connection = socket.socketpair()
        with client, connection:
            client.sendall((f"POST /convert HTTP/1.1\r\nHost: converter\r\n"
                            f"Content-Type: multipart/form-data; boundary={BOUNDARY}\r\n"
                            f"Content-Length: {len(body)}\r\n\r\n").encode() + body)
            client.shutdown(socket.SHUT_WR)
            server.Handler(connection, ("127.0.0.1", 0), None)
            connection.close()
            response = client.makefile("rb").read()
        head, payload = response.split(b"\r\n\r\n", 1)
        status, *header_lines = head.decode("latin-1").split("\r\n")
        headers = dict(line.split(": ", 1) for line in header_lines)
        return status, headers, payload

    def test_non_latin_file_name_with_a_semicolon_converts(self):
        status, headers, payload = self.post(multipart(file_part("Война и мир; книга 1.mobi")))

        self.assertEqual(status, "HTTP/1.1 200 OK")
        self.assertEqual(payload, b"EPUB")
        self.assertEqual(self.converted, ["source.mobi"])
        self.assertEqual(headers["Content-Disposition"],
                         server.content_disposition("Война и мир; книга 1.epub"))


if __name__ == "__main__":
    unittest.main()
