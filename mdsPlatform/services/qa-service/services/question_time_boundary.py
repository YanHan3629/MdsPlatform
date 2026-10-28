"""Translate only explicit endpoint semantics supported by the executor."""
import re

def boundary(question):
    text=re.sub(r'\s+','',question)
    if re.search(r'左开|两端(?:均|都)?不(?:包含|包括|含)|不(?:包含|包括|含)(?:两端|起点)|排除起点|开区间|\(start,end[\]\)]',text,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 当前时间执行器只支持包含起点的窗口，请明确可支持的端点口径。')
    open_pattern=r'左闭右开|右开|不(?:包含|包括|含|计入|纳入)终点|终点不(?:包含|包括|含|计入|纳入)|排除终点|\[start,end\)'
    opened=bool(re.search(open_pattern,text,re.I))
    # Strip negative endpoint phrases before looking for positive phrases;
    # otherwise "不包含终点" would also match its "包含终点" substring.
    positive=re.sub(open_pattern,' ',text,flags=re.I)
    closed=bool(re.search(r'(?<!不)含两端|(?:包含|包括)两端|两端(?:均|都)?(?:包含|包括|含)|含起止|起止(?:均|都)?(?:包含|包括)|'
        r'(?:包含|包括)(?:起点|开始)(?:和|与|及)(?:终点|结束)|(?:包含|包括|计入)终点|终点(?:也|必须)?(?:包含|包括|计入)|右闭|闭区间|\[start,end\]',positive,re.I))
    if closed and opened:
        raise ValueError('NEEDS_CLARIFICATION: 时间窗口的终点包含条件相互冲突，请明确一种口径。')
    if not closed and not opened and re.search(r'两端|(?:包含|包括|计入|排除).{0,6}(?:终点|起点)|(?:终点|起点).{0,6}(?:含|包|计入)',text):
        raise ValueError('NEEDS_CLARIFICATION: 本次端点包含条件尚不能唯一解析，请明确是否包含终点。')
    return '[start,end]' if closed else '[start,end)'
