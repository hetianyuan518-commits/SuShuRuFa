export const SYLLABLES: string[] = `a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo e ei en eng er fa fan fang fei fen feng fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu lv lve luan lun luo ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nv nve nuan nuo o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo`.split(/\s+/).filter((s: string) => s.length > 0)

function buildSyllableSet(): Set<string> {
  let set: Set<string> = new Set<string>()
  for (let i = 0; i < SYLLABLES.length; i++) {
    set.add(SYLLABLES[i])
  }
  return set
}

function buildPrefixSet(): Set<string> {
  let set: Set<string> = new Set<string>()
  for (let i = 0; i < SYLLABLES.length; i++) {
    let syl = SYLLABLES[i]
    for (let len = 1; len <= syl.length; len++) {
      set.add(syl.substring(0, len))
    }
  }
  return set
}

// 模块加载时一次性构建 O(1) 查找结构，替代热路径（组词/分段）上对整表的线性扫描。
const SYLLABLE_SET: Set<string> = buildSyllableSet()
const SYLLABLE_PREFIX_SET: Set<string> = buildPrefixSet()

export function hasSyllable(text: string): boolean {
  return text.length > 0 && SYLLABLE_SET.has(text)
}

export function isSyllablePrefix(text: string): boolean {
  // 归一化两侧再比较，避免大小写/空白导致漏配（与调用方的输入约定一致）。
  const key = text.trim().toLowerCase()
  if (!key) {
    return false
  }
  return SYLLABLE_PREFIX_SET.has(key)
}
