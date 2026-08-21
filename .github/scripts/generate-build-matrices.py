#!/usr/bin/env python3
"""
Generates one or more GitHub Actions build matrices containing only the
extension modules affected by the current push/PR.

Usage:
    python generate-build-matrices.py <base_ref> <head_ref> [--all]

Writes to $GITHUB_OUTPUT:
    matrix-count=<n>            number of matrix chunks produced
    matrix0=<json>              first chunk (list of {"module": "src/en/foo"})
    matrix1=<json>              second chunk, etc.

Why chunked: a single GitHub Actions matrix is capped at 256 jobs. When a
change touches shared/multisrc code (core/, lib/, gradle files) we have to
rebuild everything, which can exceed that cap for a large repo, so we split
into multiple matrix outputs and the workflow fans out one job-matrix per
chunk.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

MAX_MATRIX_SIZE = 256

GLOBAL_TRI
