"""Outils communs pour construire les cartes HALO 720 v3 avec l'API pcbnew de KiCad 9."""
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

import pcbnew

FPLIB = "/usr/share/kicad/footprints/"
MM = pcbnew.FromMM


def vec(x, y):
    return pcbnew.VECTOR2I(MM(x), MM(y))


def read_netlist(sch):
    """Composants (ref -> dict) et nets (ref, pad) -> net depuis le schéma."""
    xml = os.path.join(tempfile.gettempdir(), os.path.basename(sch) + ".xml")
    subprocess.run(["kicad-cli", "sch", "export", "netlist", "--format", "kicadxml", "-o", xml, sch],
                   check=True, capture_output=True)
    root = ET.parse(xml).getroot()
    comps = {}
    for c in root.find("components"):
        ref = c.get("ref")
        fields = {f.get("name"): (f.text or "") for f in c.iter("field")}
        comps[ref] = dict(ref=ref, value=c.findtext("value"), footprint=c.findtext("footprint") or "",
                          lcsc=fields.get("LCSC", ""), mpn=fields.get("MPN", ""))
    pins = {}
    for n in root.find("nets"):
        name = n.get("name")
        if name.startswith("unconnected-(") and "/" in name:
            name = "unconnected-(" + name[len("unconnected-("):-1].replace("/", "{slash}") + ")"
        for node in n.findall("node"):
            pins[(node.get("ref"), node.get("pin"))] = name
    return comps, pins


def load_fp(fpid, projlib):
    lib, name = fpid.split(":")
    path = projlib if lib == "halo720" else FPLIB + lib + ".pretty"
    fp = pcbnew.FootprintLoad(path, name)
    if fp is None:
        raise RuntimeError(f"empreinte introuvable : {fpid}")
    fp.SetFPID(pcbnew.LIB_ID(lib, name))
    return fp


def get_net(board, name):
    ni = board.FindNet(name)
    if ni is None:
        ni = pcbnew.NETINFO_ITEM(board, name)
        board.Add(ni)
    return ni


def place(board, comps, pins, ref, x, y, rot=0, bottom=False, projlib=None):
    c = comps[ref]
    fp = load_fp(c["footprint"], projlib)
    fp.SetReference(ref)
    fp.SetValue(c["value"])
    for k in ("LCSC", "MPN"):
        v = c[k.lower()]
        if v:
            fp.SetField(k, v)
    board.Add(fp)
    fp.SetPosition(vec(x, y))
    if bottom:
        fp.Flip(fp.GetPosition(), pcbnew.FLIP_DIRECTION_LEFT_RIGHT)
    fp.SetOrientationDegrees(rot)
    for p in fp.Pads():
        num = p.GetNumber()
        net = pins.get((ref, num))
        if net:
            p.SetNet(get_net(board, net))
    return fp


def add_outline(board, pts, width=0.1):
    for i in range(len(pts)):
        a, b = pts[i], pts[(i + 1) % len(pts)]
        s = pcbnew.PCB_SHAPE(board)
        s.SetShape(pcbnew.SHAPE_T_SEGMENT)
        s.SetStart(vec(*a))
        s.SetEnd(vec(*b))
        s.SetLayer(pcbnew.Edge_Cuts)
        s.SetWidth(MM(width))
        board.Add(s)


def add_zone(board, net, layer, pts, clearance=0.2, min_w=0.2, priority=0, thermal=True):
    z = pcbnew.ZONE(board)
    z.SetLayer(layer)
    if net:
        z.SetNet(get_net(board, net))
    ol = z.Outline()
    ol.NewOutline()
    for x, y in pts:
        ol.Append(MM(x), MM(y))
    z.SetLocalClearance(MM(clearance))
    z.SetMinThickness(MM(min_w))
    z.SetAssignedPriority(priority)
    z.SetPadConnection(pcbnew.ZONE_CONNECTION_THERMAL if thermal else pcbnew.ZONE_CONNECTION_FULL)
    z.SetThermalReliefGap(MM(0.25))
    z.SetThermalReliefSpokeWidth(MM(0.3))
    z.SetIslandRemovalMode(pcbnew.ISLAND_REMOVAL_MODE_ALWAYS)
    board.Add(z)
    return z


def add_keepout(board, pts, layers):
    z = pcbnew.ZONE(board)
    z.SetIsRuleArea(True)
    z.SetDoNotAllowTracks(True)
    z.SetDoNotAllowVias(True)
    z.SetDoNotAllowCopperPour(True)
    z.SetDoNotAllowPads(True)
    z.SetDoNotAllowFootprints(False)
    ls = pcbnew.LSET()
    for l in layers:
        ls.AddLayer(l)
    z.SetLayerSet(ls)
    ol = z.Outline()
    ol.NewOutline()
    for x, y in pts:
        ol.Append(MM(x), MM(y))
    board.Add(z)
    return z


def add_track(board, net, layer, a, b, w):
    t = pcbnew.PCB_TRACK(board)
    t.SetStart(vec(*a))
    t.SetEnd(vec(*b))
    t.SetWidth(MM(w))
    t.SetLayer(layer)
    t.SetNet(get_net(board, net))
    board.Add(t)
    return t


def add_via(board, net, p, d=0.5, drill=0.3):
    v = pcbnew.PCB_VIA(board)
    v.SetPosition(vec(*p))
    v.SetWidth(MM(d))
    v.SetDrill(MM(drill))
    v.SetViaType(pcbnew.VIATYPE_THROUGH)
    v.SetNet(get_net(board, net))
    board.Add(v)
    return v


def set_rules(board, clearance=0.15, track=0.15, via=0.5, drill=0.3, layers=4):
    ds = board.GetDesignSettings()
    board.SetCopperLayerCount(layers)
    ds.m_MinClearance = MM(0.1)
    ds.m_TrackMinWidth = MM(0.1)
    ds.m_ViasMinSize = MM(0.45)
    ds.m_MinThroughDrill = MM(0.2)
    ds.m_CopperEdgeClearance = MM(0.3)
    ds.m_HoleToHoleMin = MM(0.25)
    nc = ds.m_NetSettings.GetDefaultNetclass()
    nc.SetClearance(MM(clearance))
    nc.SetTrackWidth(MM(track))
    nc.SetViaDiameter(MM(via))
    nc.SetViaDrill(MM(drill))
