#define _GNU_SOURCE
#if defined(__has_include)
#if __has_include(<elf.h>)
#include <elf.h>
#elif __has_include(<libelf/libelf.h>)
#include <libelf/libelf.h>
#else
#error "An ELF header implementation is required"
#endif
#else
#include <elf.h>
#endif
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "roadcast_vhal.h"

static void write_file(const char *path, const void *data, size_t size) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    assert(fd >= 0);
    assert(write(fd, data, size) == (ssize_t)size);
    close(fd);
}

static void test_elf_truncated_header(void) {
    const char *path = "/tmp/test_elf_truncated.bin";
    uint8_t buf[32] = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0};
    write_file(path, buf, sizeof(buf));

    uint64_t values[ROADCAST_FRAME_COUNT] = {0};
    uint64_t load_vaddr = 0, load_offset = 0;
    int rc = roadcast_vhal_resolve_symbols(path, values, &load_vaddr, &load_offset);
    assert(rc < 0);
    assert(errno == ENOEXEC);
    unlink(path);
}

static void test_elf_bad_endian(void) {
    const char *path = "/tmp/test_elf_bad_endian.bin";
    Elf64_Ehdr ehdr;
    memset(&ehdr, 0, sizeof(ehdr));
    memcpy(ehdr.e_ident, ELFMAG, SELFMAG);
    ehdr.e_ident[EI_CLASS] = ELFCLASS64;
    ehdr.e_ident[EI_DATA] = ELFDATA2MSB; /* Big endian instead of LSB */
    ehdr.e_ident[EI_VERSION] = EV_CURRENT;
    ehdr.e_version = EV_CURRENT;
    ehdr.e_ehsize = sizeof(Elf64_Ehdr);

    write_file(path, &ehdr, sizeof(ehdr));

    uint64_t values[ROADCAST_FRAME_COUNT] = {0};
    uint64_t load_vaddr = 0, load_offset = 0;
    int rc = roadcast_vhal_resolve_symbols(path, values, &load_vaddr, &load_offset);
    assert(rc < 0);
    assert(errno == ENOEXEC);
    unlink(path);
}

static void test_elf_bad_sizes(void) {
    const char *path = "/tmp/test_elf_bad_sizes.bin";
    Elf64_Ehdr ehdr;
    memset(&ehdr, 0, sizeof(ehdr));
    memcpy(ehdr.e_ident, ELFMAG, SELFMAG);
    ehdr.e_ident[EI_CLASS] = ELFCLASS64;
    ehdr.e_ident[EI_DATA] = ELFDATA2LSB;
    ehdr.e_ident[EI_VERSION] = EV_CURRENT;
    ehdr.e_version = EV_CURRENT;
    ehdr.e_ehsize = sizeof(Elf64_Ehdr) - 1; /* Invalid ehsize */

    write_file(path, &ehdr, sizeof(ehdr));

    uint64_t values[ROADCAST_FRAME_COUNT] = {0};
    uint64_t load_vaddr = 0, load_offset = 0;
    int rc = roadcast_vhal_resolve_symbols(path, values, &load_vaddr, &load_offset);
    assert(rc < 0);
    assert(errno == ENOEXEC);
    unlink(path);
}

static void test_elf_valid_and_nonterminated_string(void) {
    const char *path = "/tmp/test_elf_synth.bin";

    /* Construct a minimal valid ELF64 with PT_LOAD and SHT_SYMTAB */
    uint8_t buffer[4096];
    memset(buffer, 0, sizeof(buffer));

    Elf64_Ehdr *ehdr = (Elf64_Ehdr *)buffer;
    memcpy(ehdr->e_ident, ELFMAG, SELFMAG);
    ehdr->e_ident[EI_CLASS] = ELFCLASS64;
    ehdr->e_ident[EI_DATA] = ELFDATA2LSB;
    ehdr->e_ident[EI_VERSION] = EV_CURRENT;
    ehdr->e_type = ET_EXEC;
    ehdr->e_machine = EM_X86_64;
    ehdr->e_version = EV_CURRENT;
    ehdr->e_ehsize = sizeof(Elf64_Ehdr);
    ehdr->e_phentsize = sizeof(Elf64_Phdr);
    ehdr->e_phnum = 1;
    ehdr->e_phoff = sizeof(Elf64_Ehdr);

    /* Program header */
    Elf64_Phdr *phdr = (Elf64_Phdr *)(buffer + ehdr->e_phoff);
    phdr->p_type = PT_LOAD;
    phdr->p_offset = 0;
    phdr->p_vaddr = 0x400000;
    phdr->p_paddr = 0x400000;
    phdr->p_filesz = sizeof(buffer);
    phdr->p_memsz = sizeof(buffer);
    phdr->p_flags = PF_R | PF_X;
    phdr->p_align = 0x1000;

    /* Section headers */
    size_t shoff = sizeof(Elf64_Ehdr) + sizeof(Elf64_Phdr);
    ehdr->e_shoff = shoff;
    ehdr->e_shentsize = sizeof(Elf64_Shdr);
    ehdr->e_shnum = 3; /* 0: null, 1: symtab, 2: strtab */

    Elf64_Shdr *shdr0 = (Elf64_Shdr *)(buffer + shoff);
    memset(shdr0, 0, sizeof(*shdr0));

    Elf64_Shdr *shdr_sym = (Elf64_Shdr *)(buffer + shoff + sizeof(Elf64_Shdr));
    Elf64_Shdr *shdr_str = (Elf64_Shdr *)(buffer + shoff + 2 * sizeof(Elf64_Shdr));

    size_t data_offset = shoff + 3 * sizeof(Elf64_Shdr);
    /* String table data: "\0MMI_Rx_085\0MMI_Rx_123" without trailing NUL for second symbol */
    char strtab[] = "\0MMI_Rx_085\0MMI_Rx_123";
    size_t strtab_size = sizeof(strtab) - 1; /* Note: omit last NUL for MMI_Rx_123 */
    memcpy(buffer + data_offset, strtab, strtab_size);

    shdr_str->sh_type = SHT_STRTAB;
    shdr_str->sh_offset = data_offset;
    shdr_str->sh_size = strtab_size;
    shdr_str->sh_entsize = 0;

    size_t sym_offset = data_offset + strtab_size;
    /* 3 symbols: 0: UNDEF, 1: MMI_Rx_085 (valid), 2: MMI_Rx_123 (non-NUL terminated) */
    Elf64_Sym syms[3];
    memset(syms, 0, sizeof(syms));

    /* sym 1: MMI_Rx_085 */
    syms[1].st_name = 1;
    syms[1].st_value = 0x401085;
    syms[1].st_shndx = 1; /* Defined */

    /* sym 2: MMI_Rx_123 (st_name starts at 12, runs to end of strtab with no NUL) */
    syms[2].st_name = 12;
    syms[2].st_value = 0x401123;
    syms[2].st_shndx = 1;

    memcpy(buffer + sym_offset, syms, sizeof(syms));

    shdr_sym->sh_type = SHT_SYMTAB;
    shdr_sym->sh_offset = sym_offset;
    shdr_sym->sh_size = sizeof(syms);
    shdr_sym->sh_entsize = sizeof(Elf64_Sym);
    shdr_sym->sh_link = 2; /* Points to shdr_str */

    size_t total_file_size = sym_offset + sizeof(syms);
    write_file(path, buffer, total_file_size);

    uint64_t values[ROADCAST_FRAME_COUNT] = {0};
    uint64_t load_vaddr = 0, load_offset = 0;
    int rc = roadcast_vhal_resolve_symbols(path, values, &load_vaddr, &load_offset);
    assert(rc == 0);
    assert(load_vaddr == 0x400000);
    assert(load_offset == 0);

    /* Verify frame 0x085 was found and resolved */
    size_t idx_085 = SIZE_MAX;
    size_t idx_123 = SIZE_MAX;
    for (size_t i = 0; i < ROADCAST_FRAME_COUNT; i++) {
        if (ROADCAST_CAN_IDS[i] == 0x085) idx_085 = i;
        if (ROADCAST_CAN_IDS[i] == 0x123) idx_123 = i;
    }
    assert(idx_085 != SIZE_MAX);
    assert(values[idx_085] == 0x401085);

    /* Verify frame 0x123 was NOT resolved because string was not NUL-terminated */
    if (idx_123 != SIZE_MAX) {
        assert(values[idx_123] == 0);
    }

    unlink(path);
}

int main(void) {
    test_elf_truncated_header();
    test_elf_bad_endian();
    test_elf_bad_sizes();
    test_elf_valid_and_nonterminated_string();
    puts("vhal elf tests passed");
    return 0;
}
