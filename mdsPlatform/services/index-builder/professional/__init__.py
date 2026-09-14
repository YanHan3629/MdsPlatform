"""Extensible parsing and professional-index pipeline.

The legacy CLIP bundle remains supported.  New semantic spaces are emitted as
an extensible ``indexes`` list in the bundle manifest.
"""

from .pipeline import build_professional_indexes

__all__ = ["build_professional_indexes"]
