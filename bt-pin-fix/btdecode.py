#!/usr/bin/env python3
"""btsnoop decoder: HCI cmd/evt with full opcode names + L2CAP signalling + RFCOMM."""
import struct, sys, datetime

EVT_NAMES = {
    0x01:"Inquiry Complete",0x02:"Inquiry Result",0x03:"Connection Complete",
    0x04:"Connection Request",0x05:"Disconnection Complete",0x06:"Authentication Complete",
    0x07:"Remote Name Request Complete",0x08:"Encryption Change",
    0x0B:"Read Remote Supported Features Complete",0x0C:"Read Remote Version Complete",
    0x0E:"Command Complete",0x0F:"Command Status",0x10:"Hardware Error",
    0x12:"Role Change",0x13:"Number Of Completed Packets",0x14:"Mode Change",
    0x16:"PIN Code Request",0x17:"Link Key Request",0x18:"Link Key Notification",
    0x1A:"Data Buffer Overflow",0x1B:"Max Slots Change",0x1C:"Read Clock Offset Complete",
    0x1D:"Conn Packet Type Changed",0x20:"Page Scan Repetition Mode Change",
    0x22:"Inquiry Result with RSSI",
    0x23:"Read Remote Extended Features Complete",0x2C:"User Passkey Notification",
    0x2D:"Keypress Notification",0x2E:"Remote Host Supported Features Notification",
    0x30:"IO Capability Request",0x31:"IO Capability Response",
    0x32:"User Confirmation Request",0x33:"User Passkey Request",
    0x34:"Remote OOB Data Request",0x35:"Simple Pairing Complete",
    0x36:"Link Supervision Timeout Changed",0x38:"Enhanced Flush Complete",
    0x3E:"LE Meta Event",0x2F:"Extended Inquiry Result",0x57:"Auth Payload Timeout Expired",
    0xFF:"Vendor Specific",
}
STATUS_NAMES = {
    0x00:"SUCCESS",0x01:"UNKNOWN_HCI_COMMAND",0x02:"UNKNOWN_CONNECTION_ID",
    0x03:"HARDWARE_FAILURE",0x04:"PAGE_TIMEOUT",0x05:"AUTHENTICATION_FAILURE",
    0x06:"PIN_OR_KEY_MISSING",0x07:"MEMORY_CAPACITY_EXCEEDED",0x08:"CONNECTION_TIMEOUT",
    0x09:"CONNECTION_LIMIT_EXCEEDED",0x0A:"SYNC_CONN_LIMIT_EXCEEDED",
    0x0B:"ACL_CONNECTION_ALREADY_EXISTS",
    0x0C:"COMMAND_DISALLOWED",0x0D:"CONNECTION_REJECTED_LIMITED_RESOURCES",
    0x0E:"CONNECTION_REJECTED_SECURITY",0x0F:"CONNECTION_REJECTED_BD_ADDR",
    0x10:"CONNECTION_ACCEPT_TIMEOUT",0x11:"UNSUPPORTED_FEATURE_OR_PARAM",
    0x12:"INVALID_HCI_PARAMETERS",0x13:"REMOTE_USER_TERMINATED",
    0x14:"REMOTE_LOW_RESOURCES",0x15:"REMOTE_POWER_OFF",
    0x16:"LOCAL_HOST_TERMINATED",0x17:"REPEATED_ATTEMPTS",0x18:"PAIRING_NOT_ALLOWED",
    0x19:"UNKNOWN_LMP_PDU",0x1A:"UNSUPPORTED_REMOTE_FEATURE",0x1F:"UNSPECIFIED_ERROR",
    0x20:"UNSUPPORTED_LMP_PARAM",0x21:"ROLE_CHANGE_NOT_ALLOWED",
    0x22:"LL_RESPONSE_TIMEOUT",0x23:"LMP_ERROR_TRANSACTION_COLLISION",
    0x24:"LMP_PDU_NOT_ALLOWED",0x25:"ENCRYPTION_MODE_NOT_ACCEPTABLE",
    0x26:"LINK_KEY_CANNOT_BE_CHANGED",0x28:"INSTANT_PASSED",
    0x2A:"DIFFERENT_TRANSACTION_COLLISION",0x3D:"CONNECTION_TERMINATED_MIC_FAILURE",
    0x3E:"CONNECTION_FAILED_TO_BE_ESTABLISHED",0x3F:"MAC_CONNECTION_FAILED",
}
OPCODES = {
    0x0401:"Inquiry",0x0402:"Inquiry Cancel",0x0405:"Create Connection",
    0x0406:"Disconnect",0x0408:"Create Connection Cancel",0x0409:"Accept Connection Request",
    0x040A:"Reject Connection Request",0x040B:"Link Key Request Reply",
    0x040C:"Link Key Request Negative Reply",0x040D:"PIN Code Request Reply",
    0x040E:"PIN Code Request Negative Reply",0x040F:"Change Conn Packet Type",
    0x0411:"Authentication Requested",0x0413:"Set Conn Encryption",
    0x0419:"Remote Name Request",0x041A:"Remote Name Request Cancel",
    0x041B:"Read Remote Supported Features",0x041C:"Read Remote Extended Features",
    0x041D:"Read Remote Version Information",0x041F:"Read Clock Offset",
    0x042A:"Reject Sync Conn Request",0x042B:"IO Capability Request Reply",
    0x042C:"User Confirmation Request Reply",0x042D:"User Confirmation Req Neg Reply",
    0x042E:"User Passkey Request Reply",0x042F:"User Passkey Req Neg Reply",
    0x0434:"IO Capability Request Negative Reply",
    0x0801:"Hold Mode",0x0803:"Sniff Mode",0x0804:"Exit Sniff Mode",
    0x0809:"Role Discovery",0x080B:"Switch Role",0x080C:"Read Link Policy Settings",
    0x080D:"Write Link Policy Settings",0x080E:"Read Default Link Policy",
    0x080F:"Write Default Link Policy",0x0811:"Sniff Subrating",
    0x0C01:"Set Event Mask",0x0C03:"Reset",0x0C05:"Set Event Filter",
    0x0C13:"Change Local Name",0x0C14:"Read Local Name",
    0x0C16:"Write Conn Accept Timeout",0x0C18:"Write Page Timeout",
    0x0C1A:"Write Scan Enable",0x0C1C:"Write Page Scan Activity",
    0x0C1E:"Write Inquiry Scan Activity",0x0C20:"Write Auth Enable",
    0x0C23:"Read Class Of Device",0x0C24:"Write Class Of Device",
    0x0C26:"Write Voice Setting",0x0C2D:"Write Automatic Flush Timeout",
    0x0C33:"Host Buffer Size",0x0C35:"Write Link Supervision Timeout",
    0x0C3A:"Write Current IAC LAP",0x0C45:"Write Inquiry Mode",
    0x0C47:"Write Page Scan Type",0x0C52:"Write Extended Inquiry Response",
    0x0C56:"Write Simple Pairing Mode",0x0C5B:"Write Default Erroneous Data Rep",
    0x0C63:"Set Event Mask Page 2",0x0C6D:"Write LE Host Support",
    0x0C7C:"Write Secure Connections Host Support",
    0x1001:"Read Local Version",0x1002:"Read Local Supported Commands",
    0x1003:"Read Local Supported Features",0x1005:"Read Buffer Size",
    0x1009:"Read BD_ADDR",0x100A:"Read Data Block Size",
    0x1405:"Read RSSI",
    0x2001:"LE Set Event Mask",0x2002:"LE Read Buffer Size",
    0x2006:"LE Set Adv Params",0x2008:"LE Set Adv Data",0x200A:"LE Set Adv Enable",
    0x200B:"LE Set Scan Params",0x200C:"LE Set Scan Enable",
    0x200D:"LE Create Connection",0x200E:"LE Create Connection Cancel",
    0x2011:"LE Add Dev To Whitelist",0x2013:"LE Connection Update",
    0x2016:"LE Read Remote Features",0x2019:"LE Start Encryption",
    0x201A:"LE LTK Req Reply",0x2020:"LE Remote Conn Param Req Reply",
    0x2031:"LE Set Ext Scan Params",0x2032:"LE Set Ext Scan Enable",
    0x2041:"LE Set Ext Adv Enable",0x2043:"LE Ext Create Connection",
}
L2CAP_SIG = {
    0x01:"Command Reject",0x02:"Connection Request",0x03:"Connection Response",
    0x04:"Config Request",0x05:"Config Response",0x06:"Disconnection Request",
    0x07:"Disconnection Response",0x08:"Echo Request",0x09:"Echo Response",
    0x0A:"Info Request",0x0B:"Info Response",
}
L2CAP_CONN_RESULT = {0:"SUCCESS",1:"PENDING",2:"REFUSED_PSM_NOT_SUPPORTED",
    3:"REFUSED_SECURITY_BLOCK",4:"REFUSED_NO_RESOURCES",6:"REFUSED_INVALID_SOURCE_CID"}
RFCOMM_CTRL = {0x2F:"SABM",0x63:"UA",0x0F:"DM",0x43:"DISC",0xEF:"UIH",0x03:"DM(nopoll)",
               0x73:"UA(nopoll)",0x3F:"SABM(nopoll)"}
MCC = {0x20:"PN",0x38:"NSC",0x24:"RPN",0x14:"RLS",0x38:"NSC",0x1C:"TEST",
       0x3C:"FCon",0x14:"RLS",0x0C:"MSC",0x04:"PSC",0x08:"CLD",0x28:"RPN?"}

def bdaddr(b): return ":".join(f"{x:02x}" for x in reversed(b))
def hexs(b): return " ".join(f"{x:02x}" for x in b)
def prt(b):
    return "".join(chr(c) if 32 <= c < 127 else ("\\r" if c==13 else "\\n" if c==10 else ".") for c in b)

def mcc_name(t):
    # MCC type byte: bits 2-7 are the type, bit1 = C/R, bit0 = EA
    v = t & 0xFC
    names = {0x80:"PN",0x38:"NSC",0x90:"RPN",0x50:"RLS",0x20:"TEST",0xA0:"FCon",
             0xB0:"FCoff",0xE0:"MSC",0xC0:"CLD"}
    return names.get(v, f"MCC_0x{t:02x}")

def main(path, filt=None, start=0, end=10**9, show_acl=True):
    data = open(path,"rb").read()
    assert data[:8] == b"btsnoop\x00"
    off = 16; n = 0
    t0 = None
    # map handle -> addr
    handles = {}
    pending_conn = {}
    while off + 24 <= len(data):
        orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", data[off:off+24])
        off += 24
        pkt = data[off:off+incl_len]; off += incl_len
        n += 1
        if not pkt: continue
        if t0 is None: t0 = ts
        rel = (ts - t0)/1e6
        pkt_type = pkt[0]; body = pkt[1:]
        d = "<-" if (flags & 1) else "->"
        label = None
        if pkt_type == 0x04 and len(body) >= 2:
            evt = body[0]; plen = body[1]; p = body[2:2+plen]
            name = EVT_NAMES.get(evt, f"evt_0x{evt:02x}")
            extra = ""
            if evt == 0x0E and len(p) >= 4:
                op = p[1] | (p[2]<<8); st = p[3]
                extra = f"[{OPCODES.get(op,hex(op))}] status={STATUS_NAMES.get(st,hex(st))}"
                if op == 0x0405: extra += " " + hexs(p[4:12])
            elif evt == 0x0F and len(p) >= 4:
                st = p[0]; op = p[2] | (p[3]<<8)
                extra = f"[{OPCODES.get(op,hex(op))}] status={STATUS_NAMES.get(st,hex(st))}"
            elif evt == 0x03 and len(p) >= 11:
                st=p[0]; h=p[1]|(p[2]<<8); a=bdaddr(p[3:9])
                if st==0: handles[h]=a
                extra=f"status={STATUS_NAMES.get(st,hex(st))} handle=0x{h:04x} addr={a} type={p[9]} enc={p[10]}"
            elif evt == 0x05 and len(p) >= 4:
                st=p[0]; h=p[1]|(p[2]<<8); r=p[3]
                extra=f"status={STATUS_NAMES.get(st,hex(st))} handle=0x{h:04x} reason={STATUS_NAMES.get(r,hex(r))}"
            elif evt == 0x06 and len(p)>=3:
                extra=f"status={STATUS_NAMES.get(p[0],hex(p[0]))} handle=0x{p[1]|(p[2]<<8):04x}"
            elif evt == 0x07 and len(p) >= 9:
                extra=f"status={STATUS_NAMES.get(p[0],hex(p[0]))} addr={bdaddr(p[1:7])} name={p[7:].split(b(chr(0)))[0] if False else p[7:].split(bytes([0]))[0]!r}"
            elif evt == 0x08 and len(p)>=4:
                extra=f"status={STATUS_NAMES.get(p[0],hex(p[0]))} handle=0x{p[1]|(p[2]<<8):04x} enc={p[3]}"
            elif evt == 0x12 and len(p)>=8:
                extra=f"status={STATUS_NAMES.get(p[0],hex(p[0]))} addr={bdaddr(p[1:7])} role={'slave' if p[7] else 'master'}"
            elif evt == 0x14 and len(p)>=6:
                extra=f"status={STATUS_NAMES.get(p[0],hex(p[0]))} handle=0x{p[1]|(p[2]<<8):04x} mode={p[3]} interval={p[4]|(p[5]<<8)}"
            elif evt in (0x16,0x17,0x18,0x30,0x04) and len(p)>=6:
                extra=f"addr={bdaddr(p[0:6])}"
            elif evt == 0x35 and len(p)>=7:
                extra=f"status={STATUS_NAMES.get(p[0],hex(p[0]))} addr={bdaddr(p[1:7])}"
            elif evt == 0x2F and len(p)>=14:
                extra=f"addr={bdaddr(p[1:7])} rssi={struct.unpack('b',p[13:14])[0]} eir={prt(p[14:])[:60]}"
            elif evt == 0x22 and len(p)>=14:
                extra=f"addr={bdaddr(p[1:7])}"
            elif evt == 0x13:
                extra=""
                name=None
            if name:
                label = f"EVT {name} {extra}".strip()
        elif pkt_type == 0x01 and len(body) >= 3:
            op = body[0] | (body[1]<<8); plen = body[2]; p = body[3:3+plen]
            extra = ""
            if op == 0x0405 and len(p)>=6:
                extra=f"addr={bdaddr(p[0:6])} pkt_type=0x{p[6]|(p[7]<<8):04x} psrm={p[8]} clkoff=0x{p[10]|(p[11]<<8):04x} allow_role_switch={p[12] if len(p)>12 else '?'}"
            elif op in (0x0419,0x040B,0x040C,0x040D,0x040E,0x042B,0x0434) and len(p)>=6:
                extra=f"addr={bdaddr(p[0:6])}"
                if op==0x040D and len(p)>=7: extra+=f" pinlen={p[6]} pin={p[7:7+p[6]]!r}"
                if op==0x040B and len(p)>=22: extra+=f" key={hexs(p[6:22])}"
                if op==0x042B and len(p)>=9: extra+=f" iocap={p[6]} oob={p[7]} auth={p[8]}"
            elif op == 0x0406 and len(p)>=3:
                extra=f"handle=0x{p[0]|(p[1]<<8):04x} reason={STATUS_NAMES.get(p[2],hex(p[2]))}"
            elif op == 0x0408 and len(p)>=6:
                extra=f"addr={bdaddr(p[0:6])}"
            elif op in (0x0411,0x0413,0x041B,0x041D,0x080B) and len(p)>=2:
                extra=f"handle/param=0x{p[0]|(p[1]<<8):04x}"
            elif op == 0x0803 and len(p)>=10:
                extra=f"handle=0x{p[0]|(p[1]<<8):04x} maxint={p[2]|(p[3]<<8)} minint={p[4]|(p[5]<<8)}"
            elif op == 0x0804 and len(p)>=2:
                extra=f"handle=0x{p[0]|(p[1]<<8):04x}"
            label = f"CMD {OPCODES.get(op,f'opcode 0x{op:04x}')} {extra}".strip()
        elif pkt_type == 0x02 and show_acl:
            h = body[0] | ((body[1] & 0x0F)<<8)
            pb = (body[1] >> 4) & 0x3
            dlen = body[2] | (body[3]<<8)
            payload = body[4:4+dlen]
            desc = f"ACL h=0x{h:04x} pb={pb} len={dlen}"
            if pb in (2,0) and len(payload) >= 4:
                l2len = payload[0]|(payload[1]<<8); cid = payload[2]|(payload[3]<<8)
                inner = payload[4:4+l2len]
                if cid == 0x0001:  # signalling
                    q = inner
                    while len(q) >= 4:
                        code=q[0]; ident=q[1]; slen=q[2]|(q[3]<<8); sp=q[4:4+slen]
                        sname = L2CAP_SIG.get(code, f"sig_0x{code:02x}")
                        s=""
                        if code==0x02 and len(sp)>=4: s=f"psm=0x{sp[0]|(sp[1]<<8):04x} scid=0x{sp[2]|(sp[3]<<8):04x}"
                        elif code==0x03 and len(sp)>=8:
                            res=sp[4]|(sp[5]<<8)
                            s=f"dcid=0x{sp[0]|(sp[1]<<8):04x} scid=0x{sp[2]|(sp[3]<<8):04x} result={L2CAP_CONN_RESULT.get(res,hex(res))} status={sp[6]|(sp[7]<<8)}"
                        elif code==0x04 and len(sp)>=4: s=f"dcid=0x{sp[0]|(sp[1]<<8):04x} opts={hexs(sp[4:])}"
                        elif code==0x05 and len(sp)>=6: s=f"scid=0x{sp[0]|(sp[1]<<8):04x} result={sp[4]|(sp[5]<<8)}"
                        elif code in (0x06,0x07) and len(sp)>=4: s=f"dcid=0x{sp[0]|(sp[1]<<8):04x} scid=0x{sp[2]|(sp[3]<<8):04x}"
                        elif code==0x0A and len(sp)>=2: s=f"type={sp[0]|(sp[1]<<8)}"
                        elif code==0x0B and len(sp)>=4: s=f"type={sp[0]|(sp[1]<<8)} result={sp[2]|(sp[3]<<8)} {hexs(sp[4:])}"
                        elif code==0x01: s=hexs(sp)
                        desc += f" | L2CAP-SIG {sname} id={ident} {s}"
                        q = q[4+slen:]
                elif cid == 0x0040 or cid >= 0x0040:
                    # possibly RFCOMM
                    r = inner
                    if len(r) >= 3:
                        addr=r[0]; ctrl=r[1]
                        dlci = addr >> 2
                        cr = (addr>>1)&1
                        cname = RFCOMM_CTRL.get(ctrl & 0xEF if False else ctrl, None)
                        if cname is None:
                            cname = RFCOMM_CTRL.get(ctrl & ~0x10, f"ctrl_0x{ctrl:02x}")
                        pf = (ctrl>>4)&1
                        # length
                        if r[2] & 1:
                            ln = r[2]>>1; body2 = r[3:3+ln]
                        else:
                            ln = (r[2]>>1) | (r[3]<<7); body2 = r[4:4+ln]
                        extra=""
                        if dlci==0 and (ctrl & ~0x10)==0xEF and len(body2)>=2:
                            extra=f" {mcc_name(body2[0])} {hexs(body2)}"
                        elif (ctrl & ~0x10)==0xEF and ln>0:
                            extra=f" data={prt(body2)!r}"
                        desc += f" | RFCOMM cid=0x{cid:04x} dlci={dlci} cr={cr} {cname} pf={pf} len={ln}{extra}"
                    else:
                        desc += f" | cid=0x{cid:04x} {hexs(inner)}"
                else:
                    desc += f" | cid=0x{cid:04x} {hexs(inner)[:80]}"
            elif pb == 1:
                desc += f" cont {prt(payload)[:80]!r}"
            label = desc
        if label:
            line = f"#{n:05d} +{rel:9.3f}s {d} {label}"
            if filt is None or filt.lower() in line.lower():
                if start <= n <= end: print(line)

if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("path"); ap.add_argument("--filter"); ap.add_argument("--start",type=int,default=0)
    ap.add_argument("--end",type=int,default=10**9); ap.add_argument("--no-acl",action="store_true")
    a = ap.parse_args()
    main(a.path, a.filter, a.start, a.end, not a.no_acl)
