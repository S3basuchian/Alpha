% Graph 5-colouring — the search-hard L&W 2018 benchmark ("no grounding problem
% but requires efficient search"). Alpha one-predicate-per-colour idiom: each
% colour is guessed via mutual negation over the other four (encoding "exactly
% one colour per vertex" directly in the guess), and a proper-colouring
% constraint forbids equal colours across every edge.
%
% Both parts are graph-independent (quantified over v/1 and e/2), so they are the
% fixed rule base while the graph (v/1 vertices, e/2 edges) streams in as facts.
% This mirrors colorRules() in IncrementalColoringBenchmark.java exactly.

c1(V) :- v(V), not c2(V), not c3(V), not c4(V), not c5(V).
c2(V) :- v(V), not c1(V), not c3(V), not c4(V), not c5(V).
c3(V) :- v(V), not c1(V), not c2(V), not c4(V), not c5(V).
c4(V) :- v(V), not c1(V), not c2(V), not c3(V), not c5(V).
c5(V) :- v(V), not c1(V), not c2(V), not c3(V), not c4(V).

:- e(V,W), c1(V), c1(W).
:- e(V,W), c2(V), c2(W).
:- e(V,W), c3(V), c3(W).
:- e(V,W), c4(V), c4(W).
:- e(V,W), c5(V), c5(W).
