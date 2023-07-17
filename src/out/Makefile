
# only compile files that start with proc
sources = $(wildcard proc*1b*actual*.c)
targets = $(sources:.c=)

.PHONY: all run clean cat superclean

all: 
	for f in $(sources); do \
		cc -o $${f%.c} $$f; \
		echo "---- $$f ----"; \
		timeout 1 ./$${f%.c}; \
	done || true
	rm -f $(targets)


verifyall:
	for f in $(sources); do \
		echo "---- $$f ----"; \
		cbmc -DCBMC --compact-trace --verbosity 4 $$f | grep -v "Unwinding"; \
	done

verify: proci1b_staged_mul.actual.c
	cbmc -DCBMC --compact-trace --verbosity 4 proci1b_staged_mul.actual.c

run:
	./run.sh 

cat:
	bat $(sources)

clean:
	rm -f $(targets) 

superclean:
	rm -f $(targets) $(wildcard proc*.actual*)


