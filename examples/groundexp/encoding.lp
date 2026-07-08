% Ground Explosion (Example 1 from Leutgeb & Weinzierl 2018, "Techniques for
% Efficient Lazy-Grounding ASP Solving"). The canonical lazy-grounding
% benchmark: a single rule with a 6-fold cross-product over chosen domain
% elements explodes eager grounders, while lazy grounders only realise the
% one ground rule corresponding to the chosen element.
%
% Choice: select at most one element from the domain via the standard
% mutual-negation idiom.
%
%   sel(X)  :- dom(X), not nsel(X).
%   nsel(X) :- dom(X), not sel(X).
%   :- sel(X), sel(Y), X != Y.
%
% Cross-product head: p/6 over the chosen element, six times. The
% encoding's body is six `sel/1` literals; under eager grounding, gringo
% must consider every (X1,...,X6) tuple where each Xi could be true,
% giving |dom|^6 candidate ground rules.
%
%   p(X1,X2,X3,X4,X5,X6) :- sel(X1),sel(X2),sel(X3),sel(X4),sel(X5),sel(X6).
%
% Reproducing the L&W 2018 Table 1 result: at |dom| = 18 clingo memouts
% with 8 GB heap (18^6 ≈ 34 M ground rules). Lazy grounding answers in
% essentially constant time per shot, since exactly one p atom is
% realised per chosen element.
%
% All positive body literals are starting literals (every variable is
% bound by the literal it appears in), so the wake-up fix applies and
% adding new dom facts retriggers the rules via the dom-starting variants
% of `sel/nsel` and the sel-starting variants of `p`.

sel(X)  :- dom(X), not nsel(X).
nsel(X) :- dom(X), not sel(X).
:- sel(X), sel(Y), X != Y.

p(X1, X2, X3, X4, X5, X6) :- sel(X1), sel(X2), sel(X3), sel(X4), sel(X5), sel(X6).
