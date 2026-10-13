"""Resolve repository documentation links for standalone distribution guides."""
from pathlib import Path
import re
from urllib.parse import quote, urlsplit


def render_document(root, source, destinations, commit):
    root = Path(root).resolve()
    document = root / source

    def link(match):
        url = match.group(1)
        parts = urlsplit(url)
        if parts.scheme or parts.netloc or not parts.path or parts.path.startswith('/'):
            return match.group(0)
        target = (document.parent / parts.path).resolve()
        try:
            relative = target.relative_to(root).as_posix()
        except ValueError:
            return match.group(0)
        if relative in destinations:
            destination = destinations[relative]
        elif target.exists():
            kind = 'tree' if target.is_dir() else 'blob'
            destination = f'https://github.com/turesheim/eclipse-timekeeper/{kind}/{commit}/{quote(relative)}'
        else:
            raise ValueError(f'Missing documentation link in {source}: {parts.path}')
        if parts.query:
            destination += '?' + parts.query
        if parts.fragment:
            destination += '#' + parts.fragment
        return '](' + destination + ')'

    return re.sub(r'\]\(([^\s)]+)\)', link, document.read_text())
