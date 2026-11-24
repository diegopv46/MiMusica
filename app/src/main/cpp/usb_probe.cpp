// Etapa 1-2 del controlador USB propio: compilar código nativo y LEER (sin escribir nada)
// qué ofrece el DAC. No reclama interfaces, no cambia alternativas, no interrumpe el audio.
#include <jni.h>
#include <linux/types.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <vector>

#ifndef USBDEVFS_GET_CAPABILITIES
#define USBDEVFS_GET_CAPABILITIES _IOR('U', 26, __u32)
#endif

namespace {

void addf(std::string& out, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    out += buf;
}

int u16(const uint8_t* p) { return p[0] | (p[1] << 8); }
int u24(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16); }

struct Stream {
    int cfg = 0, iface = 0, alt = 0, proto = 0;
    int channels = 0, bits = 0, subslot = 0;
    std::vector<int> rates;
    bool continuous = false;
    int rateLo = 0, rateHi = 0;
    bool hasOut = false;
    int outAddr = 0, outAttr = 0, outMax = 0, outInt = 0;
    bool hasFb = false;
    int fbAddr = 0;
};

const char* protoName(int p) {
    switch (p) {
        case 0x00: return "UAC1";
        case 0x20: return "UAC2";
        case 0x30: return "UAC3";
        default: return "clase de audio";
    }
}

const char* syncName(int attr) {
    switch ((attr >> 2) & 3) {
        case 0: return "sin sincronía";
        case 1: return "asíncrono";
        case 2: return "adaptativo";
        default: return "síncrono";
    }
}

std::string parseDescriptors(const std::vector<uint8_t>& d, std::set<int>& ifaces) {
    std::string out;
    std::vector<Stream> streams;
    int cfg = 0;
    int acIface = -1, acProto = -1, bcdAdc = 0;
    bool inAc = false;
    int cur = -1;
    size_t i = 0;
    const size_t n = d.size();
    while (i + 2 <= n) {
        const int len = d[i];
        if (len < 2 || i + len > n) break;
        const int type = d[i + 1];
        const uint8_t* p = &d[i];
        if (type == 0x01 && len >= 12) {
            addf(out, "Dispositivo USB %04x:%04x\n", u16(p + 8), u16(p + 10));
        } else if (type == 0x02) {
            cfg++;
        } else if (type == 0x04 && len >= 9) {
            const int ifn = p[2], alt = p[3], cls = p[5], sub = p[6], proto = p[7];
            ifaces.insert(ifn);
            inAc = (cls == 1 && sub == 1);
            if (inAc && acIface < 0) { acIface = ifn; acProto = proto; }
            cur = -1;
            if (cls == 1 && sub == 2 && alt > 0) {
                Stream s;
                s.cfg = cfg; s.iface = ifn; s.alt = alt; s.proto = proto;
                streams.push_back(s);
                cur = static_cast<int>(streams.size()) - 1;
            }
        } else if (type == 0x24 && len >= 4) {
            const int st = p[2];
            if (inAc && st == 0x01 && len >= 5) {
                bcdAdc = u16(p + 3);
            } else if (cur >= 0) {
                Stream& s = streams[cur];
                if (st == 0x01 && s.proto == 0x20 && len >= 16) {
                    s.channels = p[10];
                } else if (st == 0x02) {
                    if (s.proto == 0x20 && len >= 6 && p[3] == 1) {
                        s.subslot = p[4];
                        s.bits = p[5];
                    } else if (s.proto != 0x20 && len >= 8 && p[3] == 1) {
                        s.channels = p[4]; s.subslot = p[5]; s.bits = p[6];
                        const int nf = p[7];
                        if (nf == 0 && len >= 14) {
                            s.continuous = true;
                            s.rateLo = u24(p + 8);
                            s.rateHi = u24(p + 11);
                        } else {
                            for (int k = 0; k < nf && 8 + 3 * k + 3 <= len; ++k) s.rates.push_back(u24(p + 8 + 3 * k));
                        }
                    }
                }
            }
        } else if (type == 0x05 && len >= 7 && cur >= 0) {
            Stream& s = streams[cur];
            const int addr = p[2], attr = p[3];
            if ((attr & 3) == 1) {  // isócrono
                const int usage = (attr >> 4) & 3;
                if ((addr & 0x80) == 0) {
                    s.hasOut = true; s.outAddr = addr; s.outAttr = attr; s.outMax = u16(p + 4); s.outInt = p[6];
                } else if (usage == 1 || usage == 2) {
                    s.hasFb = true; s.fbAddr = addr;
                }
            }
        }
        i += len;
    }

    if (acIface >= 0) {
        addf(out, "Clase de audio (control): %s, bcdADC 0x%04x, interfaz de control %d\n", protoName(acProto), bcdAdc, acIface);
    } else {
        out += "Clase de audio (control): no detectada\n";
    }
    int shown = 0;
    for (const Stream& s : streams) {
        if (!s.hasOut) continue;
        shown++;
        addf(out, "\nConfiguración %d · interfaz %d · alternativa %d (%s)\n", s.cfg, s.iface, s.alt, protoName(s.proto));
        addf(out, "  %d canal(es), %d bits útiles en contenedor de %d bytes\n", s.channels, s.bits, s.subslot);
        if (s.proto == 0x20) {
            out += "  frecuencias: las define el reloj del DAC (UAC2); se leerán en una etapa siguiente\n";
        } else if (s.continuous) {
            addf(out, "  frecuencias: continuo de %d a %d Hz\n", s.rateLo, s.rateHi);
        } else {
            out += "  frecuencias:";
            for (int r : s.rates) addf(out, " %d", r);
            out += " Hz\n";
        }
        addf(out, "  salida: endpoint 0x%02x, isócrono %s, %d bytes por paquete, intervalo %d\n", s.outAddr, syncName(s.outAttr), s.outMax, s.outInt);
        if (s.hasFb) addf(out, "  realimentación: endpoint 0x%02x\n", s.fbAddr);
    }
    if (shown == 0) out += "\nNo encontré ninguna alternativa de streaming con salida isócrona.\n";
    return out;
}

std::string usbfsInfo(int fd, const std::set<int>& ifaces) {
    std::string out = "Accesos de bajo nivel (usbfs), solo lectura:\n";
    uint32_t caps = 0;
    if (ioctl(fd, USBDEVFS_GET_CAPABILITIES, &caps) == 0) {
        addf(out, "  capacidades: 0x%08x%s\n", caps, (caps & 0x20) ? " (incluye MMAP)" : "");
    } else {
        addf(out, "  capacidades: error %d (%s)\n", errno, strerror(errno));
    }
    usbdevfs_connectinfo ci;
    memset(&ci, 0, sizeof(ci));
    if (ioctl(fd, USBDEVFS_CONNECTINFO, &ci) == 0) {
        addf(out, "  conexión: dispositivo %u, %s\n", ci.devnum, ci.slow ? "velocidad baja" : "velocidad normal o alta");
    } else {
        addf(out, "  conexión: error %d (%s)\n", errno, strerror(errno));
    }
    for (int ifn : ifaces) {
        usbdevfs_getdriver gd;
        memset(&gd, 0, sizeof(gd));
        gd.interface = static_cast<unsigned int>(ifn);
        if (ioctl(fd, USBDEVFS_GETDRIVER, &gd) == 0) {
            addf(out, "  interfaz %d: controlador del sistema \"%s\"\n", ifn, gd.driver);
        } else if (errno == ENODATA) {
            addf(out, "  interfaz %d: sin controlador\n", ifn);
        } else {
            addf(out, "  interfaz %d: error %d (%s)\n", ifn, errno, strerror(errno));
        }
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_version(JNIEnv* env, jobject) {
    return env->NewStringUTF("usbaudio 0.1 (arm64-v8a, C++17)");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_probe(JNIEnv* env, jobject, jint fd, jbyteArray desc) {
    std::vector<uint8_t> d;
    if (desc != nullptr) {
        const jsize n = env->GetArrayLength(desc);
        d.resize(static_cast<size_t>(n));
        env->GetByteArrayRegion(desc, 0, n, reinterpret_cast<jbyte*>(d.data()));
    }
    std::set<int> ifaces;
    std::string out = parseDescriptors(d, ifaces);
    out += "\n";
    out += usbfsInfo(fd, ifaces);
    return env->NewStringUTF(out.c_str());
}
