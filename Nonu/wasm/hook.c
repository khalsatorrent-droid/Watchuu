#include <stdint.h>
#include <stdlib.h>

extern unsigned char __heap_base;

void* hook_alloc(int32_t len) {
  return malloc(len);
}

void hook_free(void* ptr, int32_t len) {
  free(ptr);
}

extern void hook_patch_init(const uint8_t *buf, int32_t len);
extern int32_t hook_decrypt_cenc(const uint8_t *init, int32_t init_len, const uint8_t *key, uint8_t *buf, int32_t len);
extern int32_t hook_decrypt_fragment(int32_t handle, uint8_t *buf, int32_t len);
extern int32_t hook_repair_alac(uint8_t *buf, int32_t len, const uint8_t *init, int32_t init_len);
extern int32_t hook_prefetch_template(void);
extern int32_t hook_template_load(const uint8_t *buf, int32_t len);
extern void hook_template_free(int32_t handle);
extern const char* hook_error_ptr(void);
extern int32_t hook_error_len(void);