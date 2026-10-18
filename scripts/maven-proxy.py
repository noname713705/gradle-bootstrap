#!/usr/bin/env python3
"""Plain-HTTP mirror of the Maven repositories, for builders that cannot reach them over https.

Gradle 1.x downloads with Apache HttpClient 4.1, which opens TLS connections without SNI: Maven Central (and
repo.gradle.org) refuse them on Java 7 ("peer not authenticated"), and Gradle 1.x cannot run builds on Java 8.
This proxy serves http://localhost:<port>/<prefix>/<path> and fetches https://<upstream>/<path> itself.
Downloads are cached in work/maven-proxy-cache (only successful responses).

usage: maven-proxy.py [port]   (default 8765)
prefixes:
  /maven2/                     -> https://repo1.maven.org/maven2/
  /gradle-libs-releases/       -> https://repo.gradle.org/gradle/libs-releases/
  /gradle-libs-milestones/     -> https://repo.gradle.org/gradle/libs-milestones/
  /gradle-libs/                -> https://repo.gradle.org/gradle/libs/
  /googleapis/                 -> https://ajax.googleapis.com/ajax/libs/ (javascript libraries, e.g. jquery)
"""
import http.server
import os
import sys
import urllib.error
import urllib.request

UPSTREAMS = {
    'maven2': 'https://repo1.maven.org/maven2/',
    'gradle-libs-releases': 'https://repo.gradle.org/gradle/libs-releases/',
    'gradle-libs-milestones': 'https://repo.gradle.org/gradle/libs-milestones/',
    'gradle-libs': 'https://repo.gradle.org/gradle/libs/',
    'googleapis': 'https://ajax.googleapis.com/ajax/libs/',
}
CACHE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'work', 'maven-proxy-cache')


class Handler(http.server.BaseHTTPRequestHandler):
    def upstream(self):
        prefix, _, rest = self.path.lstrip('/').partition('/')
        if prefix not in UPSTREAMS or '..' in rest:
            return None, None
        return UPSTREAMS[prefix] + rest, os.path.join(CACHE, prefix, rest)

    def serve(self, with_body):
        url, cached = self.upstream()
        if not url:
            self.send_error(404)
            return
        if not os.path.isfile(cached):
            try:
                with urllib.request.urlopen(url, timeout=60) as resp:
                    data = resp.read()
            except urllib.error.HTTPError as e:
                self.send_error(e.code)
                return
            except Exception as e:  # network errors
                self.send_error(502, str(e))
                return
            os.makedirs(os.path.dirname(cached), exist_ok=True)
            with open(cached + '.part', 'wb') as f:
                f.write(data)
            os.replace(cached + '.part', cached)
        size = os.path.getsize(cached)
        self.send_response(200)
        self.send_header('Content-Length', str(size))
        self.send_header('Content-Type', 'application/octet-stream')
        self.end_headers()
        if with_body:
            with open(cached, 'rb') as f:
                self.wfile.write(f.read())

    def do_GET(self):
        self.serve(True)

    def do_HEAD(self):
        self.serve(False)

    def log_message(self, fmt, *args):  # quiet
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()


if __name__ == '__main__':
    main()
