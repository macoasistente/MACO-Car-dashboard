import numpy as np, sys
from PIL import Image, ImageFilter
src = Image.open('/mnt/project-files/dashboard/sophie-cutout.png').convert('RGBA')
W,H = src.size
a0 = np.asarray(src.split()[3]).astype(np.float32)/255
# defringe: shrink alpha ~1.5px and soften, kills the white studio halo
a_img = src.split()[3].filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(0.8))
a = np.asarray(a_img).astype(np.float32)/255
rgb = np.asarray(src.convert('RGB')).astype(np.float32)/255

lum = rgb @ np.array([0.2126,0.7152,0.0722],np.float32)
# 1) tone: pull white paint down to a moonlit grey, keep blacks deep, add contrast
x = np.power(rgb, 1.7)*0.68                     # exposure down, gamma deepens mids
x = x*0.92 + 0.08*(x*x)                          # slight S
# 2) cool night grade toward UI navy/cyan, mild desaturation of the studio warmth
l = (x @ np.array([0.2126,0.7152,0.0722],np.float32))[...,None]
x = l + (x-l)*0.85
x = x*np.array([0.84,0.92,1.06],np.float32)
# 3) lighting falloff: key light from upper-left; lower body and rear sink into shadow
yy,xx = np.mgrid[0:H,0:W].astype(np.float32)
ys = yy/H; xs = xx/W
fall = 1.0 - 0.62*np.clip((ys-0.30)/0.70,0,1)**1.3
fall *= 1.0 - 0.30*np.clip((xs-0.50)/0.50,0,1)
fall *= 1.0 + 0.06*np.exp(-(((xs-0.30)/0.35)**2 + ((ys-0.25)/0.25)**2))  # soft hood/roof highlight
x = x*fall[...,None]
# 4) thin rim light along the roofline and rear edge only
ab = np.asarray(Image.fromarray((a*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.2))).astype(np.float32)/255
gy,gx = np.gradient(ab)
mag = np.hypot(gx,gy)+1e-6
edge = np.clip(mag*6,0,1)
facing = np.clip((gy/mag)*0.9 + (-gx/mag)*0.5, 0, 1)**2   # up-facing and right-facing contour
upper = np.clip(1.2-ys*1.6,0,1)                             # fade out below the beltline
rim = edge*facing*upper*a
rim = np.asarray(Image.fromarray((np.clip(rim,0,1)*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))).astype(np.float32)/255
x = x + rim[...,None]*np.array([0.35,0.70,1.0],np.float32)*0.45
x = np.clip(x,0,1)

car = np.dstack([x, a]).astype(np.float32)

# 5) ground shadow on a taller canvas
pad = int(H*0.12)
CH = H+pad
out = np.zeros((CH,W,4),np.float32)
# shadow: elliptical contact shadow from the alpha footprint bottom
cols = np.where(a0.max(0)>0.5)[0]; x0,x1 = cols.min(), cols.max()
rows = np.where(a0.max(1)>0.5)[0]; ybot = rows.max()
sh = Image.new('L',(W,CH),0)
from PIL import ImageDraw
d = ImageDraw.Draw(sh)
cx = (x0+x1)/2; rw = (x1-x0)*0.50; rh = H*0.055
d.ellipse([cx-rw, ybot-rh*0.9, cx+rw, ybot+rh*1.1], fill=200)
sh = sh.filter(ImageFilter.GaussianBlur(28))
d2 = Image.new('L',(W,CH),0); dd = ImageDraw.Draw(d2)
dd.ellipse([cx-rw*0.92, ybot-rh*0.35, cx+rw*0.92, ybot+rh*0.35], fill=235)  # tight contact core
d2 = d2.filter(ImageFilter.GaussianBlur(9))
sa = np.maximum(np.asarray(sh),np.asarray(d2)).astype(np.float32)/255*0.85
out[...,:3] = np.array([0.0,0.01,0.03],np.float32)
out[...,3] = sa
# composite car over shadow
ca = car[...,3:4]
dst = out[:H]
oa = ca + dst[...,3:4]*(1-ca)
dst[...,:3] = (car[...,:3]*ca + dst[...,:3]*dst[...,3:4]*(1-ca))/np.maximum(oa,1e-6)
dst[...,3:4] = oa
img = Image.fromarray((np.clip(out,0,1)*255+0.5).astype(np.uint8),'RGBA')
img.save('/mnt/project-files/dashboard/sophie-dark.png', optimize=True)
img.resize((880, round(880*CH/W)), Image.LANCZOS).save('/mnt/project-files/dashboard/sophie-dark.webp', quality=88, method=6)
carimg = Image.fromarray((np.clip(car,0,1)*255+0.5).astype(np.uint8),'RGBA')
carimg.resize((880, round(880*H/W)), Image.LANCZOS).save('/mnt/project-files/dashboard/sophie-dark-noshadow.webp', quality=88, method=6)

# before/after preview on the UI bg with glow blobs
def stage(im):
    w,h = im.size
    bg = Image.new('RGBA',(w+120,h+120),(5,7,12,255))
    g = Image.new('RGBA',bg.size,(0,0,0,0)); gd = ImageDraw.Draw(g)
    gd.ellipse([0,0,bg.size[0]*0.6,bg.size[1]*0.8],fill=(15,76,117,140))
    gd.ellipse([bg.size[0]*0.45,bg.size[1]*0.2,bg.size[0],bg.size[1]],fill=(58,47,143,120))
    bg = Image.alpha_composite(bg,g.filter(ImageFilter.GaussianBlur(120)))
    bg.alpha_composite(im,(60,60)); return bg
before = Image.open('/mnt/project-files/dashboard/sophie-cutout.png').convert('RGBA')
bb = Image.new("RGBA",(W+120,CH+120),(5,7,12,255)); bb.paste(stage(before),(0,0))
af = stage(img)
cmp = Image.new('RGB',(bb.size[0]*2+20, af.size[1]),(5,7,12))
cmp.paste(bb.convert('RGB'),(0,0)); cmp.paste(af.convert('RGB'),(bb.size[0]+20,0))
cmp.save('compare.jpg',quality=88)
