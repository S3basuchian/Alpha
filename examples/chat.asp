motive(a).
motive(b).

guilty(X) :- motive(X), not innocent(X).
innocent(X) :- motive(X), not guilty(X).