"""Shared explicit-document read version and qualification selection policy."""
import re

READ_VERSION='document-explicit-read-2026-10-23.1'
RESTRICTION=re.compile(r'源声明|(?:不是|并非|不能|不提供|不构成|未经)[^。；;\n]{0,24}(?:实测|测量|推断|放行|认证)|仅[^。；;\n]{0,12}(?:展示|示意|局部)|简化模型')
UNITS=re.compile(r'(?:单位|币种|时区|实测值|计量单位|currency|timezone|\bunit\b)\s*[:：]?',re.I)

def context_kind(text):
    return 'source_qualification' if RESTRICTION.search(text) else 'unit_or_time_context' if UNITS.search(text) else None
