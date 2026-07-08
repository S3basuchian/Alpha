% Single-source reachability (Leutgeb & Weinzierl 2018, "Techniques for Efficient
% Lazy-Grounding ASP Solving", Sect. 5, "Reachability").
%
% "a simple positive program computing reachability in a large graph. The task
%  is: given some start vertex of a graph, compute the set of all vertices
%  reachable from the start vertex."
%
% Positive program, no negation -> exactly one answer set (the reachable set).
% Unlike the transitive-closure-plus-boolean-query variant kept in
% encoding-tc-query.lp, this materialises the full forward-reachable *set* from
% a fixed start vertex, matching the original Alpha paper's benchmark exactly.
%
% The graph grows one edge-chunk at a time (streaming additions); the start
% vertex is fixed at 1 (the random-graph generator guarantees it has out-edges).

reachable(V) :- start(V).
reachable(V) :- reachable(U), edge(U, V).

start(1).
