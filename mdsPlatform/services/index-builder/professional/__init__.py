"""Extensible parsing and professional-index pipeline.

The legacy CLIP bundle remains supported.  New semantic spaces are emitted as
an extensible ``indexes`` list in the bundle manifest.
"""

__all__ = ["build_professional_indexes"]

def __getattr__(name):
    # A bounded evidence renderer needs only its format adapter, not the
    # embedding, FAISS and dataframe runtimes imported by the index pipeline.
    if name=='build_professional_indexes':
        from .pipeline import build_professional_indexes
        return build_professional_indexes
    raise AttributeError(name)
