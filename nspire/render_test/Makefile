DEBUG = FALSE

GCC = nspire-gcc
LD  = nspire-ld
GENZEHN = genzehn

GCCFLAGS = -Wall -W -marm -std=c11
LDFLAGS = -Wl,--gc-sections
ZEHNFLAGS = --name "butterscotch-render-test"

ifeq ($(DEBUG),FALSE)
	GCCFLAGS += -Os
else
	GCCFLAGS += -O0 -g
endif

OBJS = main.o nspire_renderer.o
EXE = render_test

all: $(EXE).tns

%.o: %.c
	$(GCC) $(GCCFLAGS) -c $<

$(EXE).elf: $(OBJS)
	$(LD) $^ -o $@ $(LDFLAGS)

$(EXE).tns: $(EXE).elf
	$(GENZEHN) --input $^ --output $@.zehn $(ZEHNFLAGS)
	make-prg $@.zehn $@
	rm $@.zehn

clean:
	rm -f $(OBJS) $(EXE).elf $(EXE).tns $(EXE).zehn
