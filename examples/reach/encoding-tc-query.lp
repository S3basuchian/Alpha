% Online reachability query (Omiga "reach" family, paper-worthy variant).
%
% Given a directed graph that grows one edge-chunk at a time, decide whether
% a fixed source node can reach a fixed target node via the recursive
% transitive closure.
%
% The recursive rule below is the operative one: unlike the original Omiga
% encoding (which had a "dead" recursive rule fixed to a target that the
% query never used), here the query `target_reached` actually consumes
% `reachable(SOURCE, TARGET)`, so the grounder must compute the transitive
% closure to answer it.
%
% Under eager grounding (clingo), gringo's semi-naive evaluation materialises
% the full `reachable/2` extension reachable from any node forward (or to any
% node backward, depending on rule shape). For a graph with non-trivial
% out-degree this is O(V * |TC|) ground atoms — large, and re-paid in full
% every shot if the graph grew. Under lazy grounding (Alpha), only ground
% instances needed to certify the answer to the specific query are realised.

reachable(X, Y) :- edge(X, Y).
reachable(X, Y) :- reachable(X, Z), edge(Z, Y).

source(15842).
target(10189).
target_reached :- source(S), target(T), reachable(S, T).
