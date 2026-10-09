#ifndef KWEB_MENUS_PLATFORM_TEST_H_
#define KWEB_MENUS_PLATFORM_TEST_H_

/**
 * One provider-specific structure check per target. Each implementation returns
 * the number of failed assertions it added, so main() reports one total.
 */
int RunPlatformNativeStructureTest();

#endif
