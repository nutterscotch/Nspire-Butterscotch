DEBUG = FALSE

GCC = nspire-gcc
LD  = nspire-ld
GENZEHN = genzehn

GCCFLAGS = -Wall -W -marm -std=c11
LDFLAGS = -Wl,--gc-sections
ZEHNFLAGS = --name "butterscotch-hello"

ifeq ($(DEBUG),FALSE)
	GCCFLAGS += -Os
else
	GCCFLAGS += -O0 -g
endif

EXE = hello

all: $(EXE).tns

$(EXE).o: $(EXE).c
	$(GCC) $(GCCFLAGS) -c $<

$(EXE).elf: $(EXE).o
	$(LD) $^ -o $@ $(LDFLAGS)

$(EXE).tns: $(EXE).elf
	$(GENZEHN) --input $^ --output $@.zehn $(ZEHNFLAGS)
	make-prg $@.zehn $@
	rm $@.zehn

clean:
	rm -f $(EXE).o $(EXE).elf $(EXE).tns $(EXE).zehn
