#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <cstdio>
#include <cstring>
#include <cerrno>

static volatile sig_atomic_t running = 1;

static void stopTest(int) {
    running = 0;
}

int main() {
    signal(SIGINT, stopTest);
    signal(SIGTERM, stopTest);

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

    if (fd < 0) {
        std::fprintf(stderr, "OPEN_FAILED: %s\n", strerror(errno));
        return 1;
    }

    auto fail = [&](const char* step) {
        std::fprintf(stderr, "%s_FAILED: %s\n", step, strerror(errno));
        close(fd);
        return 1;
    };

    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) return fail("EV_SYN");
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) return fail("EV_KEY");
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) return fail("EV_ABS");

    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0)
        return fail("BTN_TOUCH");

    if (ioctl(fd, UI_SET_ABSBIT, ABS_X) < 0)
        return fail("ABS_X");

    if (ioctl(fd, UI_SET_ABSBIT, ABS_Y) < 0)
        return fail("ABS_Y");

    if (ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0)
        return fail("INPUT_PROP_DIRECT");

    uinput_user_dev device{};

    std::snprintf(
        device.name,
        UINPUT_MAX_NAME_SIZE,
        "PrecisionLoop uinput test"
    );

    device.id.bustype = BUS_VIRTUAL;
    device.id.vendor = 0x1234;
    device.id.product = 0x5678;
    device.id.version = 1;

    device.absmin[ABS_X] = 0;
    device.absmax[ABS_X] = 539;

    device.absmin[ABS_Y] = 0;
    device.absmax[ABS_Y] = 959;

    if (write(fd, &device, sizeof(device)) != sizeof(device))
        return fail("DEVICE_CONFIG");

    if (ioctl(fd, UI_DEV_CREATE) < 0)
        return fail("UI_DEV_CREATE");

    std::printf("DEVICE_CREATED\n");
    std::fflush(stdout);

    for (int i = 0; i < 60 && running; ++i)
        sleep(1);

    ioctl(fd, UI_DEV_DESTROY);
    close(fd);

    std::printf("DEVICE_DESTROYED\n");

    return 0;
}
