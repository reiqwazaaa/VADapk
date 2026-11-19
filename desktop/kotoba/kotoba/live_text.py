"""Text reconciliation for tentative captions; no model or GUI dependencies."""
import re


def units(text):
    # CJK does not have reliable whitespace boundaries.
    if re.search(r'[\u3040-\u30ff\u3400-\u9fff]', text): return list(text)
    return re.findall(r'\S+\s*', text)


class Agreement:
    def __init__(self): self.previous = []
    def update(self, text):
        current = units(text)
        count = 0
        for left, right in zip(self.previous, current):
            if left != right: break
            count += 1
        self.previous = current
        return ''.join(current[:count]), ''.join(current[count:])
    def clear(self): self.previous = []
