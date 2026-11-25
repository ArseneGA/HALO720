"""Petit routeur A* sur grille (2 couches signal F.Cu / B.Cu + vias) pour les cartes HALO 720 v3.

Principe : la carte est discrétisée à RES mm. Pour chaque net, on calcule la distance de chaque
case au cuivre des AUTRES nets (transformée de distance) ; une case est libre si cette distance
dépasse demi-largeur + garde. Le chemin trouvé est converti en pistes/vias KiCad, puis ajouté au
raster. Le DRC de KiCad reste juge de paix : il est lancé après le routage.
"""
import heapq
import math

import numpy as np
import pcbnew
from PIL import Image, ImageDraw
from scipy.ndimage import distance_transform_edt

from pcb_common import MM, add_track, add_via, get_net

RES = 0.05
LAYERS = [pcbnew.F_Cu, pcbnew.B_Cu]


class Router:
    def __init__(self, board, clearance=0.15, margin=0.05, edge=0.35, via_d=0.5, via_drill=0.25):
        self.b = board
        self.clr = clearance + margin       # garde utilisée sur la grille (marge de discrétisation)
        self.edge = edge
        self.via_d, self.via_drill = via_d, via_drill
        bb = board.GetBoardEdgesBoundingBox()
        self.x0, self.y0 = bb.GetLeft() / 1e6, bb.GetTop() / 1e6
        self.W = int(round(bb.GetWidth() / 1e6 / RES)) + 1
        self.H = int(round(bb.GetHeight() / 1e6 / RES)) + 1
        self.netcode = {}
        self.cu = [np.zeros((self.H, self.W), np.int32) for _ in LAYERS]   # code net, -1 = obstacle
        self.pads_any = np.zeros((self.H, self.W), bool)
        self.holes = np.zeros((self.H, self.W), bool)
        self.keep = np.zeros((self.H, self.W), bool)
        inside = np.ones((self.H, self.W), bool)
        inside[0, :] = inside[-1, :] = inside[:, 0] = inside[:, -1] = False
        self.edge_dist = distance_transform_edt(inside) * RES
        self.code_of = lambda n: self.netcode.setdefault(n, len(self.netcode) + 1)
        self.corridors = []        # (masque de bande, {net: masque des couloirs propres})
        self._raster_board()

    # ---------- raster ----------
    def c(self, x, y):
        return (x - self.x0) / RES, (y - self.y0) / RES

    def _bbox(self, xmin, ymin, xmax, ymax):
        i0 = max(int(np.floor((xmin - self.x0) / RES)) - 1, 0)
        i1 = min(int(np.ceil((xmax - self.x0) / RES)) + 2, self.W)
        j0 = max(int(np.floor((ymin - self.y0) / RES)) - 1, 0)
        j1 = min(int(np.ceil((ymax - self.y0) / RES)) + 2, self.H)
        xs = self.x0 + np.arange(i0, i1) * RES
        ys = self.y0 + np.arange(j0, j1) * RES
        return i0, i1, j0, j1, xs, ys

    def _poly(self, arr, pts, val):
        """Marque les cases dont le CENTRE est dans le polygone (exact)."""
        from matplotlib.path import Path
        xs_ = [p[0] for p in pts]; ys_ = [p[1] for p in pts]
        i0, i1, j0, j1, xs, ys = self._bbox(min(xs_), min(ys_), max(xs_), max(ys_))
        if i1 <= i0 or j1 <= j0:
            return
        X, Y = np.meshgrid(xs, ys)
        inside = Path(pts).contains_points(np.c_[X.ravel(), Y.ravel()], radius=1e-6).reshape(X.shape)
        sub = arr[j0:j1, i0:i1]
        sub[inside] = val

    def _disk(self, arr, x, y, r, val):
        i0, i1, j0, j1, xs, ys = self._bbox(x - r, y - r, x + r, y + r)
        X, Y = np.meshgrid(xs, ys)
        m = (X - x) ** 2 + (Y - y) ** 2 <= r * r + 1e-9
        arr[j0:j1, i0:i1][m] = val

    def _seg(self, arr, a, b, w, val):
        r = w / 2
        i0, i1, j0, j1, xs, ys = self._bbox(min(a[0], b[0]) - r, min(a[1], b[1]) - r,
                                             max(a[0], b[0]) + r, max(a[1], b[1]) + r)
        X, Y = np.meshgrid(xs, ys)
        dx, dy = b[0] - a[0], b[1] - a[1]
        L2 = dx * dx + dy * dy
        if L2 == 0:
            t_ = np.zeros_like(X)
        else:
            t_ = np.clip(((X - a[0]) * dx + (Y - a[1]) * dy) / L2, 0, 1)
        d2 = (X - a[0] - t_ * dx) ** 2 + (Y - a[1] - t_ * dy) ** 2
        arr[j0:j1, i0:i1][d2 <= r * r + 1e-9] = val

    def _pad_poly(self, pad, layer):
        ps = pad.GetEffectivePolygon(layer, pcbnew.ERROR_INSIDE)
        out = []
        for i in range(ps.OutlineCount()):
            o = ps.Outline(i)
            out.append([(o.CPoint(k).x / 1e6, o.CPoint(k).y / 1e6) for k in range(o.PointCount())])
        return out

    def _raster_board(self):
        b = self.b
        for fp in b.GetFootprints():
            for p in fp.Pads():
                net = p.GetNetname()
                code = self.code_of(net) if net and not net.startswith("unconnected-") else -1
                for li, L in enumerate(LAYERS):
                    if p.IsOnLayer(L) and p.GetAttribute() != pcbnew.PAD_ATTRIB_NPTH:
                        for poly in self._pad_poly(p, L):
                            self._poly(self.cu[li], poly, code)
                            self._poly(self.pads_any, poly, True)
                if p.HasHole():
                    pos = p.GetPosition()
                    r = max(p.GetDrillSize().x, p.GetDrillSize().y) / 2e6
                    self._disk(self.holes, pos.x / 1e6, pos.y / 1e6, r, True)
                    if p.GetAttribute() == pcbnew.PAD_ATTRIB_NPTH:
                        # trou de fixation : vis + entretoise -> rien dans un rayon de 2,8 mm
                        rk = 2.8 if r > 1.0 else r
                        for li in range(2):
                            self._disk(self.cu[li], pos.x / 1e6, pos.y / 1e6, rk, -1)
        for t in b.GetTracks():
            code = self.code_of(t.GetNetname())
            if t.Type() == pcbnew.PCB_VIA_T:
                p = t.GetPosition()
                for li in range(2):
                    self._disk(self.cu[li], p.x / 1e6, p.y / 1e6, t.GetWidth(pcbnew.F_Cu) / 2e6, code)
            else:
                li = 0 if t.GetLayer() == pcbnew.F_Cu else (1 if t.GetLayer() == pcbnew.B_Cu else None)
                if li is not None:
                    self._seg(self.cu[li], (t.GetStart().x / 1e6, t.GetStart().y / 1e6),
                              (t.GetEnd().x / 1e6, t.GetEnd().y / 1e6), t.GetWidth() / 1e6, code)
        for z in b.Zones():
            if z.GetIsRuleArea() and z.GetDoNotAllowTracks():
                ol = z.Outline().Outline(0)
                pts = [(ol.CPoint(k).x / 1e6, ol.CPoint(k).y / 1e6) for k in range(ol.PointCount())]
                self._poly(self.keep, pts, True)

    def add_corridor(self, x_a, x_b, y_a, y_b, lanes):
        """Bande [x_a,x_b]x[y_a,y_b] (mm) interdite en F.Cu sauf couloirs : {net: [(x0,x1,y0,y1)]}."""
        strip = np.zeros((self.H, self.W), bool)
        self._poly(strip, [(x_a, y_a), (x_b, y_a), (x_b, y_b), (x_a, y_b)], True)
        ld = {}
        for net, rects in lanes.items():
            m = np.zeros((self.H, self.W), bool)
            for (a, b_, c_, d_) in rects:
                self._poly(m, [(a, c_), (b_, c_), (b_, d_), (a, d_)], True)
            ld[net] = m
        self.corridors.append((strip, ld))

    # ---------- carte des cases libres ----------
    def free_maps(self, net, hw):
        code = self.code_of(net)
        free = []
        dists = []
        for li in range(2):
            other = (self.cu[li] != 0) & (self.cu[li] != code)
            d = distance_transform_edt(~other) * RES
            dists.append(d)
            f = (d >= hw + self.clr) & (self.edge_dist >= hw + self.edge) & \
                (distance_transform_edt(~self.keep) * RES >= hw + 0.1)
            if li == 0:
                for strip, lanes in self.corridors:
                    own = lanes.get(net)
                    f &= ~strip | (own if own is not None else False)
            free.append(f)
        vr = self.via_d / 2
        dh = distance_transform_edt(~self.holes) * RES
        dp = distance_transform_edt(~self.pads_any) * RES
        dk = distance_transform_edt(~self.keep) * RES
        via_ok = (dists[0] >= vr + self.clr) & (dists[1] >= vr + self.clr) & (dh >= vr + 0.3) & \
                 (dp >= vr + 0.12) & (self.edge_dist >= vr + self.edge) & (dk >= vr + 0.1)
        return free, via_ok

    def own_cells(self, net, li):
        return self.cu[li] == self.code_of(net)

    # ---------- A* ----------
    def astar(self, free, via_ok, src, dst, via_cost=30, layers=(0, 1), max_iter=4_000_000, own_holes=None):
        """src/dst : listes de tableaux booléens (une par couche). Renvoie [(li, y, x), ...]."""
        H, W = self.H, self.W
        N = H * W
        INF = np.float32(1e30)
        g = np.full(2 * N, INF, np.float32)
        par = np.full(2 * N, -1, np.int64)
        dst_flat = np.concatenate([dst[0].ravel(), dst[1].ravel()])
        dys, dxs = np.nonzero(dst[0] | dst[1])
        if len(dys) == 0:
            return None
        # heuristique : distance à la boîte englobante des cibles
        ty0, ty1, tx0, tx1 = dys.min(), dys.max(), dxs.min(), dxs.max()

        def h(y, x):
            dy = max(ty0 - y, 0, y - ty1)
            dx = max(tx0 - x, 0, x - tx1)
            return max(dx, dy) + 0.4142 * min(dx, dy)

        heap = []
        for li in layers:
            ys, xs = np.nonzero(src[li] & free[li])
            for y, x in zip(ys, xs):
                s = li * N + y * W + x
                g[s] = 0
                heapq.heappush(heap, (h(y, x), 0.0, s))
        moves = [(-1, 0, 1.0), (1, 0, 1.0), (0, -1, 1.0), (0, 1, 1.0),
                 (-1, -1, 1.4142), (-1, 1, 1.4142), (1, -1, 1.4142), (1, 1, 1.4142)]
        fr = [free[0].ravel(), free[1].ravel()]
        vo = via_ok.ravel()
        oh = own_holes.ravel() if own_holes is not None else np.zeros(N, bool)
        it = 0
        while heap:
            f_, gs, s = heapq.heappop(heap)
            if gs > g[s]:
                continue
            if dst_flat[s] and gs > 0:
                path = []
                while s != -1:
                    li, r = divmod(int(s), N)
                    path.append((li, r // W, r % W))
                    s = par[s]
                return path[::-1]
            it += 1
            if it > max_iter:
                return None
            li, r = divmod(int(s), N)
            y, x = divmod(r, W)
            for dy, dx, cst in moves:
                ny, nx = y + dy, x + dx
                if ny < 0 or nx < 0 or ny >= H or nx >= W:
                    continue
                nr = ny * W + nx
                if not fr[li][nr]:
                    continue
                if dy and dx and not (fr[li][ny * W + x] and fr[li][y * W + nx]):
                    continue
                ns = li * N + nr
                ng = gs + cst
                if ng < g[ns]:
                    g[ns] = ng
                    par[ns] = s
                    heapq.heappush(heap, (ng + h(ny, nx), ng, ns))
            if (vo[r] or oh[r]) and len(layers) == 2:
                ol = 1 - li
                ns = ol * N + r
                if fr[ol][r] or oh[r]:
                    ng = gs + (0.5 if oh[r] else via_cost)
                    if ng < g[ns]:
                        g[ns] = ng
                        par[ns] = s
                        heapq.heappush(heap, (ng + h(y, x), ng, ns))
        return None

    # ---------- conversion en pistes ----------
    def xy(self, y, x):
        return float(round(self.x0 + float(x) * RES, 4)), float(round(self.y0 + float(y) * RES, 4))

    def commit(self, net, path, w, via_d=None, via_drill=None):
        code = self.code_of(net)
        via_d = via_d or self.via_d
        via_drill = via_drill or self.via_drill
        # découpe en segments de même couche, fusion des points alignés
        segs = []
        cur = [path[0]]
        for p in path[1:]:
            if p[0] != cur[-1][0]:
                segs.append(cur)
                cur = [p]
            else:
                cur.append(p)
        segs.append(cur)
        items = 0
        for k, s in enumerate(segs):
            li = s[0][0]
            pts = [s[0]]
            for i in range(1, len(s) - 1):
                a, b_, c_ = pts[-1], s[i], s[i + 1]
                d1 = (b_[1] - a[1], b_[2] - a[2])
                d2 = (c_[1] - b_[1], c_[2] - b_[2])
                if d1[0] * d2[1] - d1[1] * d2[0] != 0 or (d1[0] * d2[0] + d1[1] * d2[1]) < 0:
                    pts.append(b_)
            if len(s) > 1:
                pts.append(s[-1])
            for a, b_ in zip(pts, pts[1:]):
                A, B = self.xy(a[1], a[2]), self.xy(b_[1], b_[2])
                if A == B:
                    continue
                add_track(self.b, net, LAYERS[li], A, B, w)
                self._seg(self.cu[li], A, B, w, code)
                items += 1
            if k < len(segs) - 1:
                y, x = s[-1][1], s[-1][2]
                P = self.xy(y, x)
                if self.holes[y, x] and self.cu[0][y, x] == code and self.cu[1][y, x] == code:
                    continue
                add_via(self.b, net, P, via_d, via_drill)
                for l2 in range(2):
                    self._disk(self.cu[l2], P[0], P[1], via_d / 2, code)
                self._disk(self.holes, P[0], P[1], via_drill / 2, True)
                items += 1
        return items

    def add_copper_track(self, net, layer_idx, a, b, w):
        add_track(self.b, net, LAYERS[layer_idx], a, b, w)
        self._seg(self.cu[layer_idx], a, b, w, self.code_of(net))

    def add_copper_via(self, net, p, d=None, drill=None):
        d = d or self.via_d
        drill = drill or self.via_drill
        add_via(self.b, net, p, d, drill)
        for l2 in range(2):
            self._disk(self.cu[l2], p[0], p[1], d / 2, self.code_of(net))
        self._disk(self.holes, p[0], p[1], drill / 2, True)

    # ---------- haut niveau ----------
    def pad_mask(self, pad):
        out = [np.zeros((self.H, self.W), bool), np.zeros((self.H, self.W), bool)]
        for li, L in enumerate(LAYERS):
            if pad.IsOnLayer(L):
                for poly in self._pad_poly(pad, L):
                    self._poly(out[li], poly, True)
        return out

    def route_net(self, net, w, via_cost=30, layers=(0, 1), log=print, refs=None):
        pads = [p for fp in self.b.GetFootprints() for p in fp.Pads() if p.GetNetname() == net
                and (refs is None or fp.GetReference() in refs)]
        if len(pads) < 2:
            return True
        hw = w / 2
        free, via_ok = self.free_maps(net, hw)
        # composante de départ = cuivre déjà connecté au premier pad (pistes comprises)
        masks = [self.pad_mask(p) for p in pads]
        connected = self.flood(net, masks[0])
        todo = list(range(1, len(pads)))
        ok = True
        while todo:
            # cible la plus proche (centre)
            c0 = np.argwhere(connected[0] | connected[1]).mean(axis=0)
            def dist(i):
                q = pads[i].GetPosition()
                cy, cx = self.c(q.x / 1e6, q.y / 1e6)[::-1]
                return (cy - c0[0]) ** 2 + (cx - c0[1]) ** 2
            todo.sort(key=dist)
            i = todo.pop(0)
            if (masks[i][0] & connected[0]).any() or (masks[i][1] & connected[1]).any():
                continue
            src = [connected[0] | (self.own_cells(net, 0) & self._grow(connected[0])),
                   connected[1] | (self.own_cells(net, 1) & self._grow(connected[1]))]
            code = self.code_of(net)
            oh = self.holes & (self.cu[0] == code) & (self.cu[1] == code)
            path = self.astar(free, via_ok, src, masks[i], via_cost=via_cost, layers=layers, own_holes=oh)
            if path is None:
                log(f"  ÉCHEC {net} -> {pads[i].GetParentFootprint().GetReference()}.{pads[i].GetNumber()}")
                ok = False
                continue
            self.commit(net, path, w)
            connected = self.flood(net, connected)
            free, via_ok = self.free_maps(net, hw)
        return ok

    def _grow(self, m):
        return m

    def flood(self, net, seed):
        """Cuivre du net relié à `seed` (couches liées par les trous : vias / pads traversants)."""
        from scipy.ndimage import label
        own = [self.cu[0] == self.code_of(net), self.cu[1] == self.code_of(net)]
        labs = [label(own[0])[0], label(own[1])[0]]
        conn = [np.zeros_like(own[0]), np.zeros_like(own[1])]
        cur = [seed[0] & own[0], seed[1] & own[1]]
        for _ in range(20):
            changed = False
            for li in range(2):
                ids = np.unique(labs[li][cur[li]])
                ids = ids[ids > 0]
                m = np.isin(labs[li], ids) | seed[li]
                if (m & ~conn[li]).any():
                    conn[li] |= m
                    changed = True
            link = conn[0] & own[1] & self.holes
            link2 = conn[1] & own[0] & self.holes
            cur = [conn[0] | link2, conn[1] | link]
            if not changed:
                break
        return conn

    def fanout(self, pad, net, w=0.3, max_len=3.0, via_d=None, via_drill=None):
        """Relie un pad à une via (vers un plan interne)."""
        hw = w / 2
        free, via_ok = self.free_maps(net, hw)
        vr = (via_d or self.via_d) / 2
        if via_d:
            # recalcul via_ok pour un diamètre différent
            pass
        src = self.pad_mask(pad)
        # cibles : cases où une via est possible, à moins de max_len du pad
        pos = pad.GetPosition()
        cx, cy = self.c(pos.x / 1e6, pos.y / 1e6)
        yy, xx = np.ogrid[:self.H, :self.W]
        near = ((yy - cy) ** 2 + (xx - cx) ** 2) <= (max_len / RES) ** 2
        tgt_layer = 0 if pad.IsOnLayer(pcbnew.F_Cu) else 1
        dst = [np.zeros_like(near), np.zeros_like(near)]
        dst[tgt_layer] = via_ok & near & free[tgt_layer] & ~(src[0] | src[1])
        path = self.astar(free, via_ok, src, dst, layers=(tgt_layer,))
        if path is None:
            return False
        if len(path) > 1:
            self.commit(net, path, w)
        y, x = path[-1][1], path[-1][2]
        self.add_copper_via(net, self.xy(y, x), via_d, via_drill)
        return True
