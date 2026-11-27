// Etapa 3 del controlador USB propio. SOLO para pruebas desde el "Laboratorio USB":
// reclamar el DAC, leer/fijar su volumen, mandar un tono por transferencias isócronas y devolverlo al sistema.
#include <jni.h>
#include <linux/types.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cerrno>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <algorithm>
#include <map>
#include <set>
#include <string>
#include <vector>

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif

namespace lab {

void addf(std::string& out, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    out += buf;
}

int u16(const uint8_t* p) { return p[0] | (p[1] << 8); }
int s16(const uint8_t* p) { return static_cast<int16_t>(p[0] | (p[1] << 8)); }

struct Alt {
    int alt = 0, channels = 0, bits = 0, subslot = 0;
    int epAddr = 0, maxPacket = 0, interval = 1, attr = 0;
    bool hasOut = false;
};

struct Session {
    int fd = -1, acIface = -1, acProto = -1, asIface = -1, clockId = -1, fuId = -1;
    std::vector<Alt> alts;
    std::set<int> audioIfaces, claimed;
};

Session S;

// ---------- lectura de descriptores (solo UAC2) ----------
std::string labParse(const std::vector<uint8_t>& d, Session& s) {
    struct Unit { int subtype, id, source, termType; };
    struct AsAlt { int iface; Alt a; };
    std::map<int, Unit> units;
    std::vector<Unit> outs;
    std::vector<AsAlt> found;
    int cfg = 0, curCls = 0, curSub = 0, curAs = -1;
    size_t i = 0;
    const size_t n = d.size();
    while (i + 2 <= n) {
        const int len = d[i];
        if (len < 2 || i + len > n) break;
        const int type = d[i + 1];
        const uint8_t* p = &d[i];
        if (type == 0x02) {
            if (++cfg > 1) break;  // solo la primera configuración
        } else if (type == 0x04 && len >= 9) {
            const int ifn = p[2], alt = p[3], cls = p[5], sub = p[6], proto = p[7];
            curCls = cls; curSub = sub; curAs = -1;
            if (cls == 1) s.audioIfaces.insert(ifn);
            if (cls == 1 && sub == 1 && s.acIface < 0) { s.acIface = ifn; s.acProto = proto; }
            if (cls == 1 && sub == 2 && alt > 0) {
                AsAlt x; x.iface = ifn; x.a.alt = alt;
                found.push_back(x);
                curAs = static_cast<int>(found.size()) - 1;
            }
        } else if (type == 0x24 && len >= 4) {
            const int st = p[2], id = p[3];
            if (curCls == 1 && curSub == 1) {
                const int term = len > 5 ? u16(p + 4) : 0;
                if (st == 0x0A && s.clockId < 0) s.clockId = id;
                else if (st == 0x02) units[id] = Unit{st, id, -1, term};
                else if (st == 0x03) { Unit u{st, id, len > 7 ? p[7] : -1, term}; units[id] = u; outs.push_back(u); }
                else if (st == 0x06) units[id] = Unit{st, id, len > 4 ? p[4] : -1, 0};
            } else if (curAs >= 0) {
                Alt& a = found[curAs].a;
                if (st == 0x01 && len >= 16) a.channels = p[10];
                else if (st == 0x02 && len >= 6 && p[3] == 1) { a.subslot = p[4]; a.bits = p[5]; }
            }
        } else if (type == 0x05 && len >= 7 && curAs >= 0) {
            Alt& a = found[curAs].a;
            const int addr = p[2], attr = p[3];
            if ((attr & 3) == 1 && (addr & 0x80) == 0) {
                a.hasOut = true; a.epAddr = addr; a.attr = attr;
                a.maxPacket = u16(p + 4) & 0x7FF; a.interval = p[6];
            }
        }
        i += len;
    }
    for (const Unit& o : outs) {
        if ((o.termType >> 8) == 0x01) continue;
        int src = o.source, hops = 0;
        while (src > 0 && hops < 6) {
            auto it = units.find(src);
            if (it == units.end()) break;
            if (it->second.subtype == 0x06) { s.fuId = it->second.id; break; }
            src = it->second.source; hops++;
        }
        if (s.fuId >= 0) break;
    }
    for (const AsAlt& x : found) {
        if (!x.a.hasOut) continue;
        if (s.asIface < 0) s.asIface = x.iface;
        if (x.iface == s.asIface) s.alts.push_back(x.a);
    }
    std::string out;
    if (s.acIface < 0) return "ERR;no encontré la interfaz de control de audio";
    if (s.acProto != 0x20) return "ERR;por ahora solo se admite UAC2";
    if (s.asIface < 0 || s.alts.empty()) return "ERR;no encontré una interfaz de streaming con salida";
    addf(out, "OK;control=%d streaming=%d reloj=%d volumen=%d alternativas=", s.acIface, s.asIface, s.clockId, s.fuId);
    for (const Alt& a : s.alts) addf(out, "[alt%d %dbits ep0x%02x max%d] ", a.alt, a.bits, a.epAddr, a.maxPacket);
    addf(out, "interfaces de audio=");
    for (int x : s.audioIfaces) addf(out, "%d ", x);
    return out;
}

int ctrl(int fd, uint8_t type, uint8_t req, uint16_t value, uint16_t index, void* data, uint16_t len, unsigned timeoutMs = 500) {
    usbdevfs_ctrltransfer ct;
    memset(&ct, 0, sizeof(ct));
    ct.bRequestType = type; ct.bRequest = req; ct.wValue = value; ct.wIndex = index;
    ct.wLength = len; ct.timeout = timeoutMs; ct.data = data;
    return ioctl(fd, USBDEVFS_CONTROL, &ct);
}

// ---------- reclamar / devolver ----------
std::string claim() {
    std::string out, errs;
    for (int ifn : S.audioIfaces) {
        usbdevfs_disconnect_claim dc;
        memset(&dc, 0, sizeof(dc));
        dc.interface = static_cast<unsigned int>(ifn);
        dc.flags = 0;  // quitarla pase lo que pase y reclamarla
        if (ioctl(S.fd, USBDEVFS_DISCONNECT_CLAIM, &dc) == 0) S.claimed.insert(ifn);
        else addf(errs, "interfaz %d: error %d (%s); ", ifn, errno, strerror(errno));
    }
    if (!errs.empty()) return "ERR;" + errs;
    addf(out, "OK;reclamadas %zu interfaces", S.claimed.size());
    return out;
}

std::string release() {
    std::string out;
    for (auto it = S.claimed.rbegin(); it != S.claimed.rend(); ++it) {
        unsigned int ifn = static_cast<unsigned int>(*it);
        if (ioctl(S.fd, USBDEVFS_RELEASEINTERFACE, &ifn) != 0) addf(out, "liberar %u: error %d (%s); ", ifn, errno, strerror(errno));
    }
    int attached = 0, already = 0, none = 0, bad = 0;
    for (int ifn : S.claimed) {
        usbdevfs_ioctl cmd;
        memset(&cmd, 0, sizeof(cmd));
        cmd.ifno = ifn;
        cmd.ioctl_code = USBDEVFS_CONNECT;
        cmd.data = nullptr;
        errno = 0;
        const int r = ioctl(S.fd, USBDEVFS_IOCTL, &cmd);
        if (r > 0) { attached++; addf(out, "interfaz %d reconectada; ", ifn); }
        else if (r == 0) { none++; addf(out, "interfaz %d: ningún controlador quiso tomarla; ", ifn); }
        else if (errno == EBUSY) { already++; addf(out, "interfaz %d ya estaba asociada; ", ifn); }
        else { bad++; addf(out, "interfaz %d: error %d (%s); ", ifn, errno, strerror(errno)); }
    }
    S.claimed.clear();
    char head[160];
    snprintf(head, sizeof(head), "%s;reconectadas: %d, ya asociadas por el sistema: %d, sin controlador: %d, con error: %d; ",
             (bad || none) ? "ERR" : "OK", attached, already, none, bad);
    return std::string(head) + out;
}

// ---------- volumen (UAC2, unidad de funciones) ----------
std::string readVolume() {
    if (S.fuId < 0) return "ERR;este DAC no tiene unidad de volumen";
    const uint16_t idx = static_cast<uint16_t>((S.fuId << 8) | S.acIface);
    std::string errs;
    for (int ch = 0; ch <= 1; ++ch) {
        uint8_t cur[2] = {0, 0}, rng[8] = {0};
        const uint16_t val = static_cast<uint16_t>((0x02 << 8) | ch);
        const int n1 = ctrl(S.fd, 0xA1, 0x01, val, idx, cur, 2);
        const int e1 = errno;
        const int n2 = ctrl(S.fd, 0xA1, 0x02, val, idx, rng, 8);
        const int e2 = errno;
        if (n1 == 2 && n2 >= 8) {
            char b[128];
            snprintf(b, sizeof(b), "OK;%d;%d;%d;%d;%d", s16(cur), s16(rng + 2), s16(rng + 4), s16(rng + 6), ch);
            return b;
        }
        addf(errs, "canal %d: actual=%d(%s) rango=%d(%s); ", ch, n1, n1 < 0 ? strerror(e1) : "ok", n2, n2 < 0 ? strerror(e2) : "ok");
    }
    return "ERR;" + errs;
}

std::string setVolume(int raw) {
    if (S.fuId < 0) return "ERR;sin unidad de volumen";
    const uint16_t idx = static_cast<uint16_t>((S.fuId << 8) | S.acIface);
    if (raw > 32767) raw = 32767;
    if (raw < -32767) raw = -32767;
    uint8_t b[2] = {static_cast<uint8_t>(raw & 0xFF), static_cast<uint8_t>((raw >> 8) & 0xFF)};
    int ok = 0;
    for (int ch = 0; ch <= 2; ++ch) {
        if (ctrl(S.fd, 0x21, 0x01, static_cast<uint16_t>((0x02 << 8) | ch), idx, b, 2) == 2) ok++;
    }
    char r[96];
    if (ok > 0) { snprintf(r, sizeof(r), "OK;canales fijados: %d", ok); return r; }
    return std::string("ERR;ningún canal aceptó el cambio (") + strerror(errno) + ")";
}

// ---------- tono por transferencias isócronas ----------
struct IsoPlan { int pps, channels, bits, subslot, maxPacket; };

struct ToneState {
    double phase = 0;
    int64_t produced = 0, total = 0, acc = 0;
    double amp = 0.015;
    double freq = 440.0;
};

// Rellena un URB isócrono. Devuelve false si algún paquete excede el máximo del endpoint.
bool fillUrb(usbdevfs_urb* urb, uint8_t* buf, int packets, const IsoPlan& plan, ToneState& st, int rate, int endpoint) {
    const int frameBytes = plan.channels * plan.subslot;
    size_t off = 0;
    const double fadeFrames = 0.3 * rate;
    for (int p = 0; p < packets; ++p) {
        st.acc += rate;
        const int frames = static_cast<int>(st.acc / plan.pps);
        st.acc -= static_cast<int64_t>(frames) * plan.pps;
        const size_t bytes = static_cast<size_t>(frames) * frameBytes;
        if (static_cast<int>(bytes) > plan.maxPacket) return false;
        for (int f = 0; f < frames; ++f) {
            double env = 1.0;
            if (st.produced < fadeFrames) env = st.produced / fadeFrames;
            const double left = static_cast<double>(st.total - st.produced);
            if (left < fadeFrames) env = std::fmin(env, left > 0 ? left / fadeFrames : 0.0);
            if (st.produced >= st.total) env = 0.0;
            const double s = st.amp * env * std::sin(st.phase);
            st.phase += 2.0 * M_PI * st.freq / rate;
            if (st.phase > 2.0 * M_PI) st.phase -= 2.0 * M_PI;
            st.produced++;
            for (int c = 0; c < plan.channels; ++c) {
                uint8_t* q = buf + off + static_cast<size_t>(f) * frameBytes + static_cast<size_t>(c) * plan.subslot;
                if (plan.subslot == 3) {
                    const int32_t v = static_cast<int32_t>(s * 8388607.0);
                    q[0] = v & 0xFF; q[1] = (v >> 8) & 0xFF; q[2] = (v >> 16) & 0xFF;
                } else if (plan.subslot == 4) {
                    const int32_t v = static_cast<int32_t>(s * 2147483647.0);
                    q[0] = v & 0xFF; q[1] = (v >> 8) & 0xFF; q[2] = (v >> 16) & 0xFF; q[3] = (v >> 24) & 0xFF;
                } else {
                    const int16_t v = static_cast<int16_t>(s * 32767.0);
                    q[0] = v & 0xFF; q[1] = (v >> 8) & 0xFF;
                }
            }
        }
        urb->iso_frame_desc[p].length = static_cast<unsigned int>(bytes);
        urb->iso_frame_desc[p].actual_length = 0;
        urb->iso_frame_desc[p].status = 0;
        off += bytes;
    }
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = static_cast<unsigned char>(endpoint);
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = buf;
    urb->buffer_length = static_cast<int>(off);
    urb->number_of_packets = packets;
    urb->status = 0;
    urb->actual_length = 0;
    urb->error_count = 0;
    return true;
}

int64_t nowMs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

std::string tone(int rate, int ms, int ampDb, int urbMs, int nUrbReq) {
    if (S.claimed.empty()) return "ERR;primero hay que reclamar el DAC";
    const Alt* chosen = nullptr;
    for (const Alt& a : S.alts) if (a.bits == 24 && a.channels == 2) { chosen = &a; break; }
    if (!chosen) for (const Alt& a : S.alts) if (a.channels == 2) { chosen = &a; break; }
    if (!chosen) return "ERR;no hay una alternativa estéreo";
    if (chosen->interval != 1) return "ERR;intervalo de endpoint no soportado";
    std::string out;

    // 1) frecuencia de muestreo en el reloj del DAC
    if (S.clockId >= 0) {
        const uint16_t cidx = static_cast<uint16_t>((S.clockId << 8) | S.acIface);
        uint8_t b[4] = {static_cast<uint8_t>(rate & 0xFF), static_cast<uint8_t>((rate >> 8) & 0xFF),
                        static_cast<uint8_t>((rate >> 16) & 0xFF), static_cast<uint8_t>((rate >> 24) & 0xFF)};
        if (ctrl(S.fd, 0x21, 0x01, 0x0100, cidx, b, 4) != 4) {
            return std::string("ERR;no pude fijar la frecuencia en el reloj (") + strerror(errno) + ")";
        }
        uint8_t r[4] = {0, 0, 0, 0};
        if (ctrl(S.fd, 0xA1, 0x01, 0x0100, cidx, r, 4) == 4) {
            const int actual = r[0] | (r[1] << 8) | (r[2] << 16) | (r[3] << 24);
            addf(out, "reloj a %d Hz; ", actual);
        }
    }
    // 2) alternativa de streaming
    usbdevfs_setinterface si;
    si.interface = static_cast<unsigned int>(S.asIface);
    si.altsetting = static_cast<unsigned int>(chosen->alt);
    if (ioctl(S.fd, USBDEVFS_SETINTERFACE, &si) != 0) {
        return std::string("ERR;no pude activar la alternativa (") + strerror(errno) + ")";
    }
    // 3) velocidad del bus
    const int speed = ioctl(S.fd, USBDEVFS_GET_SPEED);
    const bool hs = speed >= 3;
    IsoPlan plan{(hs ? 8000 : 1000), chosen->channels, chosen->bits, chosen->subslot, chosen->maxPacket};
    const int packetsPerUrb = std::max(1, std::min(128, hs ? urbMs * 8 : urbMs));
    const int nUrb = std::max(2, std::min(32, nUrbReq));
    addf(out, "bus %s (código %d), alternativa %d (%d bits), URB de %d paquetes (%d ms) x %d; ",
         hs ? "alta velocidad" : "velocidad completa", speed, chosen->alt, chosen->bits, packetsPerUrb, hs ? packetsPerUrb / 8 : packetsPerUrb, nUrb);

    const size_t urbSize = sizeof(usbdevfs_urb) + static_cast<size_t>(packetsPerUrb) * sizeof(usbdevfs_iso_packet_desc);
    std::vector<usbdevfs_urb*> urbs(nUrb, nullptr);
    std::vector<std::vector<uint8_t>> bufs(nUrb);
    for (int i = 0; i < nUrb; ++i) {
        urbs[i] = static_cast<usbdevfs_urb*>(calloc(1, urbSize));
        bufs[i].assign(static_cast<size_t>(packetsPerUrb) * plan.maxPacket, 0);
        urbs[i]->usercontext = reinterpret_cast<void*>(static_cast<intptr_t>(i));
    }
    ToneState st;
    st.total = static_cast<int64_t>(rate) * ms / 1000;
    st.amp = std::pow(10.0, ampDb / 20.0);

    int inflight = 0, submitted = 0, completed = 0, badStatus = 0, lastStatus = 0;
    int64_t t0 = nowMs(), lastDone = t0, maxGap = 0;
    std::vector<int> firstFrames, lastFrames;
    std::string fail;
    for (int i = 0; i < nUrb; ++i) {
        if (!fillUrb(urbs[i], bufs[i].data(), packetsPerUrb, plan, st, rate, chosen->epAddr)) { fail = "un paquete excede el máximo del endpoint"; break; }
        if (ioctl(S.fd, USBDEVFS_SUBMITURB, urbs[i]) != 0) { addf(fail, "SUBMITURB falló: %d (%s)", errno, strerror(errno)); break; }
        inflight++; submitted++;
    }
    while (inflight > 0 && fail.empty()) {
        pollfd pfd{S.fd, POLLOUT, 0};
        const int pr = poll(&pfd, 1, 1000);
        if (pr <= 0) {
            addf(fail, "se agotó la espera: %d completados en %lld ms, %d en vuelo, hueco máximo previo %lld ms",
                 completed, static_cast<long long>(nowMs() - t0), inflight, static_cast<long long>(maxGap));
            break;
        }
        usbdevfs_urb* done = nullptr;
        if (ioctl(S.fd, USBDEVFS_REAPURBNDELAY, &done) != 0) {
            if (errno == EAGAIN) continue;
            addf(fail, "REAPURB falló: %d (%s)", errno, strerror(errno));
            break;
        }
        inflight--; completed++;
        const int64_t t = nowMs();
        maxGap = std::max<int64_t>(maxGap, t - lastDone);
        lastDone = t;
        if (firstFrames.size() < 3) firstFrames.push_back(done->start_frame);
        lastFrames.push_back(done->start_frame);
        if (lastFrames.size() > 3) lastFrames.erase(lastFrames.begin());
        if (done->status != 0 || done->error_count != 0) { badStatus++; lastStatus = done->status; }
        if (st.produced < st.total) {
            const int i = static_cast<int>(reinterpret_cast<intptr_t>(done->usercontext));
            if (!fillUrb(done, bufs[i].data(), packetsPerUrb, plan, st, rate, chosen->epAddr)) { fail = "un paquete excede el máximo del endpoint"; break; }
            if (ioctl(S.fd, USBDEVFS_SUBMITURB, done) == 0) { inflight++; submitted++; }
            else addf(fail, "SUBMITURB falló: %d (%s)", errno, strerror(errno));
        }
    }
    if (inflight > 0) {  // salida de emergencia: cancelar lo pendiente
        for (int i = 0; i < nUrb; ++i) ioctl(S.fd, USBDEVFS_DISCARDURB, urbs[i]);
        for (int k = 0; k < 50 && inflight > 0; ++k) {
            usbdevfs_urb* done = nullptr;
            if (ioctl(S.fd, USBDEVFS_REAPURBNDELAY, &done) == 0) inflight--; else usleep(2000);
        }
    }
    si.altsetting = 0;
    ioctl(S.fd, USBDEVFS_SETINTERFACE, &si);
    for (auto* u : urbs) free(u);

    addf(out, "enviados: %d, completados: %d, con error: %d", submitted, completed, badStatus);
    if (badStatus) addf(out, " (último estado %d)", lastStatus);
    addf(out, ", hueco máximo entre completados: %lld ms, duración: %lld ms", static_cast<long long>(maxGap), static_cast<long long>(nowMs() - t0));
    out += ", start_frame inicial:";
    for (int f : firstFrames) addf(out, " %d", f);
    out += ", final:";
    for (int f : lastFrames) addf(out, " %d", f);
    return (fail.empty() ? "OK;" : "ERR;") + out + (fail.empty() ? "" : " · " + fail);
}

std::string jstr(JNIEnv* env, jstring s) { (void)env; (void)s; return ""; }

}  // namespace lab

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_labOpen(JNIEnv* env, jobject, jint fd, jbyteArray desc) {
    lab::S = lab::Session();
    lab::S.fd = fd;
    std::vector<uint8_t> d;
    if (desc != nullptr) {
        const jsize n = env->GetArrayLength(desc);
        d.resize(static_cast<size_t>(n));
        env->GetByteArrayRegion(desc, 0, n, reinterpret_cast<jbyte*>(d.data()));
    }
    return env->NewStringUTF(lab::labParse(d, lab::S).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_labClaim(JNIEnv* env, jobject) { return env->NewStringUTF(lab::claim().c_str()); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_labVolume(JNIEnv* env, jobject) { return env->NewStringUTF(lab::readVolume().c_str()); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_labSetVolume(JNIEnv* env, jobject, jint raw) { return env->NewStringUTF(lab::setVolume(raw).c_str()); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_labTone(JNIEnv* env, jobject, jint rate, jint ms, jint ampDb, jint urbMs, jint nUrb) {
    return env->NewStringUTF(lab::tone(rate, ms, ampDb, urbMs, nUrb).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_labRelease(JNIEnv* env, jobject) { return env->NewStringUTF(lab::release().c_str()); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mimusica_NativeUsb_labReset(JNIEnv* env, jobject) {
    const int r = ioctl(lab::S.fd, USBDEVFS_RESET, 0);
    std::string out = r == 0 ? "OK;dispositivo reiniciado" : std::string("ERR;no pude reiniciarlo (") + strerror(errno) + ")";
    return env->NewStringUTF(out.c_str());
}
