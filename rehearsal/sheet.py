import sys, glob, os
from PIL import Image, ImageDraw
d = sys.argv[1]; files = sorted(glob.glob(os.path.join(d, '[0-9]*.png')))
ims = [Image.open(f).convert('RGB') for f in files]
w, h = 480, 270; cols = 4; rows = (len(ims) + cols - 1) // cols
sheet = Image.new('RGB', (cols * (w + 8) + 8, rows * (h + 26) + 8), (40, 40, 40))
dr = ImageDraw.Draw(sheet)
for i, (f, im) in enumerate(zip(files, ims)):
    x, y = 8 + (i % cols) * (w + 8), 8 + (i // cols) * (h + 26)
    sheet.paste(im.resize((w, h)), (x, y)); dr.text((x + 4, y + h + 6), os.path.basename(f), fill=(230, 230, 230))
sheet.save(os.path.join(d, 'sheet.png')); print(len(ims), sheet.size)
