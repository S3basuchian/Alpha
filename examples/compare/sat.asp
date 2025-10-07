e(1,0).
p(3,0).

%p_move(-1,0) :- not p_move( 1,0).
%p_move( 1,0) :- not p_move(-1,0).
%e_move(-1,0) :- not e_move( 1,0).
%e_move( 1,0) :- not e_move(-1,0).

p_move(-1,0) | p_move( 1,0).
e_move(-1,0) | e_move( 1,0).

e(P,1) :- e(PP,0), e_move(DP,0), P = PP - DP.
p(P,1) :- p(PP,0), p_move(DP,0), P = PP - DP.

ok(0).
ok(T) :- ok(T-1), e(E,T), p(P,T), E != P.

sat :- ok(1).

:- not sat.

e_move(-1,0) :- sat.
e_move(1,0) :- sat.