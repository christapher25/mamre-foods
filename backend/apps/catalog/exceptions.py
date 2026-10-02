"""Domain errors raised by catalog services."""


class PriceHistoryError(ValueError):
    """A price already in effect cannot be rewritten; add a row with a later effective_from
    (Doc 1 s4.3: history is kept)."""
