%%%%%%% INSTANCE %%%%%%%
wall(-2,-2).wall(-2,-1).wall(-1,-1).wall(-1,0).wall(0,2).wall(1,1).wall(1,2).

fcol(2,0).
frow(0,0).

% player always starts at the 0,0 position, instance objects are in relation to this postion
pcol(0,0).
prow(0,0).

%% restrictions
% player cannot move through walls or leave the window radius
:- pcol(C,_), C = -3.
:- pcol(C,_), C = 3.
:- prow(R,_), R = -3.
:- prow(R,_), R = 3.
:- prow(R,T), pcol(C,T), wall(C,R).

%% player movement
% at each time step the player decides to move vertically or horizontally
pmove(0, T) :- T=1, not pmove(1, T).
pmove(1, T) :- T=1, not pmove(0, T).
pmove(0, T) :- T=2, not pmove(1, T).
pmove(1, T) :- T=2, not pmove(0, T).

% player moves vertically, decides wheter to move up or down
prow(R + 1,T) :- prow(R,T-1), pmove(0, T), not prow(R - 1,T).
prow(R - 1,T) :- prow(R,T-1), pmove(0, T), not prow(R + 1,T).
pcol(C,T) :- pcol(C,T-1), pmove(0, T).

% player moves horizontally, decides wheter to move left or right
pcol(C + 1,T) :- pcol(C,T-1), pmove(1, T), not pcol(C - 1,T).
pcol(C - 1,T) :- pcol(C,T-1), pmove(1, T), not pcol(C + 1,T).
prow(R,T) :- prow(R,T-1), pmove(1, T).

%% frog movement (same as player)
fmove(0, T) :- T=1, not fmove(1, T).
fmove(1, T) :- T=1, not fmove(0, T).
fmove(0, T) :- T=2, not fmove(1, T).
fmove(1, T) :- T=2, not fmove(0, T).
frow(R + 1,T) :- frow(R,T-1), fmove(0, T), not frow(R - 1,T).
frow(R - 1,T) :- frow(R,T-1), fmove(0, T), not frow(R + 1,T).
fcol(C,T) :- fcol(C,T-1), fmove(0, T).
fcol(C + 1,T) :- fcol(C,T-1), fmove(1, T), not fcol(C - 1,T).
fcol(C - 1,T) :- fcol(C,T-1), fmove(1, T), not fcol(C + 1,T).
frow(R,T) :- frow(R,T-1), fmove(1, T).

%% frog constraints (saturation)
% start of the plan is assumed to be norm compliant
ok(0).

% propagate ok(T) throughout the plan if no norm violation
ok(T) :- pcol(C,T), fcol(CC,T), C != CC, ok(T-1).
ok(T) :- prow(R,T), frow(RR,T), R != RR, ok(T-1).

% saturate once ok(T) reaches horizon
sat :- ok(2).

% also saturate if frog move is inconsistent (moves out of considered window or into a wall)
sat :- fcol(C,_), C = -3.
sat :- fcol(C,_), C = 3.
sat :- frow(R,_), R = -3.
sat :- frow(R,_), R = 3.
sat :- frow(R,T), fcol(C,T), wall(C,R).

% exclude answer sets where sat is not derived (i.e. frog norm was violated)
:- not sat.

% actually saturate to constructed answer set (frog is at every position in every step)
%fcol(C,T) :- C = -2, sat, T=1.
%fcol(C,T) :- C = -1, sat, T=1.
%fcol(C,T) :- C =  0, sat, T=1.
%fcol(C,T) :- C =  1, sat, T=1.
%fcol(C,T) :- C =  2, sat, T=1.
%fcol(C,T) :- C = -2, sat, T=2.
%fcol(C,T) :- C = -1, sat, T=2.
%fcol(C,T) :- C =  0, sat, T=2.
%fcol(C,T) :- C =  1, sat, T=2.
%fcol(C,T) :- C =  2, sat, T=2.
%frow(R,T) :- R = -2, sat, T=1.
%frow(R,T) :- R = -1, sat, T=1.
%frow(R,T) :- R =  0, sat, T=1.
%frow(R,T) :- R =  1, sat, T=1.
%frow(R,T) :- R =  2, sat, T=1.
%frow(R,T) :- R = -2, sat, T=2.
%frow(R,T) :- R = -1, sat, T=2.
%frow(R,T) :- R =  0, sat, T=2.
%frow(R,T) :- R =  1, sat, T=2.
%frow(R,T) :- R =  2, sat, T=2.
%fmove(0,T) :- T=1, sat.
fmove(0,T) :- T=2, sat.
%fmove(1,T) :- T=1, sat.
fmove(1,T) :- T=2, sat.