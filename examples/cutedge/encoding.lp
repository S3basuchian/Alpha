% Cutedge encoding (Omiga benchmark).
%
% Given a directed graph over nodes 0..N-1, choose one edge to "delete" and
% compute reachability to node 98 (the target) under the surviving edges.
%
% The encoding is intentionally recursion-heavy: under eager grounding,
% reachable(X,98) :- reachable(X,Z), reachable(Z,98) instantiates over
% every pair (X,Z), producing O(N^3) ground rules for the recursive step.
% Lazy grounding only realises ground instances actually reachable from
% the current partial assignment, which is much smaller.

delete(X,Y) :- edge(X,Y), not keep(X,Y).
keep(X,Y)   :- edge(X,Y), delete(X1,Y1), X1 != X.
keep(X,Y)   :- edge(X,Y), delete(X1,Y1), Y1 != Y.

reachable(X,Y)  :- keep(X,Y).
reachable(X,98) :- reachable(X,Z), reachable(Z,98).
