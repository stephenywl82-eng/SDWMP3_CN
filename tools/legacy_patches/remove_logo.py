# -*- coding: utf-8 -*-
fp = r'E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player\ui\screens\SongListScreen.kt'
t = open(fp, 'r', encoding='utf-8').read()

# 手机模式 (0.6f)
old1 = '''                        } else {
                            DefaultCoverImage(
                                songTitle = "Moto音乐",
                                songArtist = "",
                                modifier = Modifier.fillMaxHeight(0.6f).aspectRatio(1f)
                            )
                        }'''
new1 = '''                        }'''

# 平板模式 (0.5f)
old2 = '''                        } else {
                            DefaultCoverImage(
                                songTitle = "Moto音乐",
                                songArtist = "",
                                modifier = Modifier.fillMaxHeight(0.5f).aspectRatio(1f)
                            )
                        }'''
new2 = '''                        }'''

c1 = t.count(old1)
c2 = t.count(old2)
t = t.replace(old1, new1)
t = t.replace(old2, new2)
open(fp, 'w', encoding='utf-8', newline='').write(t)
print('phone logo replaced: %d' % c1)
print('tablet logo replaced: %d' % c2)

# 验证 DefaultCoverImage 在顶栏的引用是否清干净
import re
t2 = open(fp, 'r', encoding='utf-8').read()
lines = t2.split('\n')
for i, line in enumerate(lines, 1):
    if 'songTitle = "Moto' in line or ('DefaultCoverImage' in line and 'Moto' in line):
        print('REMAIN %d: %s' % (i, line.rstrip()))
