edge(1,2).
edge(2,3).
edge(3,1).
edge(1,4).
edge(4,5).
edge(5,1).

node(1).
node(2).
node(3).
node(4).
node(5).

colored(N, red) :- node(N), not colored(N, green), not colored(N, blue).
colored(N, blue) :- node(N), not colored(N, red), not colored(N, green).
colored(N, green) :- node(N), not colored(N, blue), not colored(N, red).

:- edge(X, Y), colored(X, red), colored(Y, red).
:- edge(X, Y), colored(X, green), colored(Y, green).
:- edge(X, Y), colored(X, blue), colored(Y, blue).