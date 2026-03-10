# 数据清洗节点
# 清理原始数据中的空值，并输出是否有数据

def main(inputs):
    """
    清洗数据，移除空值

    Args:
        inputs: 包含 raw_data 的字典

    Returns:
        包含 cleaned_data 和 has_data 的字典
    """
    data = inputs.get('raw_data', [])
    cleaned = [x for x in data if x is not None]
    return {
        'cleaned_data': cleaned,
        'has_data': len(cleaned) > 0
    }
