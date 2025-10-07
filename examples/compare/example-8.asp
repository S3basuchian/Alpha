e(red,green).
e(red,blue).
e(green,blue).
e(X,Y) :- e(Y,X).

:- e(X1,X2), e(X2,X3), e(X3,X1), e(X1,X4), e(X4,X5), e(X5,X1).