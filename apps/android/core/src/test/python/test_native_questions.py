"""Native form parsing/submission: isolated fixtures, no production terminal input."""
import io
import unittest
from unittest.mock import patch
from test_bridge import b


def screen(selected=0, title='请选择配色：', answer='Other', page='1 of 2'):
    rows=['• Queued follow-up inputs', '', '  '+page, '  '+title, '']
    for i,label in enumerate(['浅色','深色',answer]):
        rows.append(('  › ' if i==selected else '    ')+str(i+1)+'. '+label)
    index,count=map(int,page.split(' of '))
    footer='  enter submit   ctrl + ] skip   alt + ↓ '+('prev question' if index>1 else 'main prompt')
    if index<count: footer+='   shift + ← next question'
    return '\n'.join(rows+['',footer,'',''])


class NativeQuestionTests(unittest.TestCase):
    def test_form_contains_exact_question_and_choices(self):
        q=b.native_question(screen())
        self.assertEqual(q['title'],'请选择配色：')
        self.assertEqual(q['options'],['浅色','深色','Other'])
        self.assertEqual((q['index'],q['count'],q['previous'],q['next']),(1,2,False,True))
        self.assertEqual(q['id'],b.native_question(screen(selected=2))['id'])
        self.assertNotEqual(q['id'],b.native_question(screen(title='新的问题'))['id'])
        self.assertNotEqual(q['id'],b.native_question(screen(page='2 of 2'))['id'])

    def test_unknown_approval_truncated_or_existing_draft_has_no_native_form(self):
        for value in ['Approve command?\n› 1. Yes', screen().replace('• Queued follow-up inputs',''),
                      screen().replace('    2. 深色','    4. 深色'), screen().replace('enter submit','unknown footer'),
                      screen(answer='existing desktop draft'), screen().replace('  › 1.', '    1.')]:
            self.assertIsNone(b.native_question(value))

    def test_pending_age_does_not_invalidate_open_but_count_does(self):
        first='• Queued follow-up inputs\n  ? 2 questions · 13s\n    shift + ← to answer'
        self.assertEqual(b.screen_token(first),b.screen_token(first.replace('13s','14s')))
        self.assertNotEqual(b.screen_token(first),b.screen_token(first.replace('2 questions','3 questions')))
        self.assertIsNone(b.native_question(screen(title='中'*3000)))

    def test_free_text_question_and_existing_draft(self):
        blank='• Queued follow-up inputs\n\n  叫什么名字？\n\n  Type your answer\n\n  enter submit   ctrl + ] skip   alt + ↓ main prompt'+'\n'*20
        question=b.native_question(blank)
        self.assertTrue(question['freeText'])
        self.assertEqual(question['title'],'叫什么名字？')
        filled=blank.replace('Type your answer','第一行显示\n  后续显示')
        self.assertIsNone(b.native_question(filled))
        self.assertEqual(b.native_question(filled,'第一行显示后续显示')['id'],question['id'])

    def test_short_viewport_with_trailing_blank_rows_still_shows_question_hint(self):
        closed='• Queued follow-up inputs\n  ? 1 question\n    shift + ← to answer\n› Ask Codex to do anything'+'\n'*20
        with patch.object(b,'target',return_value={'id':'%1'}),patch.object(b,'locate',return_value=('binding',io.BytesIO(),{'id':'test'})),patch.object(b,'public_messages',return_value={}),patch.object(b,'run',return_value=closed):
            result=b.handle({'action':'snapshot','screen':True})
        self.assertTrue(result['questionHint'])
        self.assertTrue(result['questionClosed'])
        self.assertIsNone(result['question'])

    def test_wrapped_text_preserved_and_verified(self):
        value=screen(title='一个长问题\n  后续文字',answer='回答第一段\n       接续文字')
        q=b.native_question(value,'回答第一段接续文字')
        self.assertIsNotNone(q)
        self.assertEqual(q['title'],'一个长问题\n后续文字')
        self.assertIsNone(b.native_question(value,'不相同的回答'))

    def submit(self, choice, text=None, mutate=None, fail=None):
        state={'selected':0,'answer':'Other','title':'请选择配色：','buffer':b''}
        calls=[]
        initial=screen()
        def run(*args,**kwargs):
            calls.append((args,kwargs))
            name=args[1]
            if name==fail: raise b.Refused('transport lost')
            if name=='capture-pane': return screen(state['selected'],state['title'],state['answer'])
            if name=='load-buffer': state['buffer']=kwargs['data']
            if name=='paste-buffer': state['answer']=state['buffer'].decode()
            if name=='send-keys':
                if args[-1]=='Down': state['selected']+=1
                if args[-1]=='Up': state['selected']-=1
                if mutate and args[-1]!='Enter': state['title']='Another question'
            return ''
        request={'questionId':b.native_question(initial)['id'],'screenToken':b.screen_token(initial),'choice':choice}
        if text is not None: request['text']=text
        with patch.object(b,'target'),patch.object(b,'locate',side_effect=lambda _:('binding',io.BytesIO(),{})),patch.object(b,'run',side_effect=run),patch.object(b.time,'sleep'):
            result=b.submit_question(request,{'id':'%1'},'binding')
        return result,calls

    def test_single_tap_choice_moves_then_submits_once(self):
        result,calls=self.submit(1)
        self.assertTrue(result['submitted'])
        self.assertEqual([a[-1] for a,_ in calls if a[1]=='send-keys'],['Down','Enter'])

    def test_free_answer_pastes_literal_then_submits_once(self):
        text='我希望选择自定义 $(touch forbidden)'
        result,calls=self.submit(2,text)
        self.assertTrue(result['submitted'])
        self.assertEqual([a[-1] for a,_ in calls if a[1]=='send-keys'],['Down','Down','Enter'])
        self.assertEqual([k['data'] for _,k in calls if 'data' in k],[text.encode()])
        self.assertFalse(any(text in a for a,_ in calls))

    def test_changed_question_never_gets_enter(self):
        result,calls=self.submit(1,mutate=True)
        self.assertTrue(result['uncertain'])
        self.assertFalse(any(a[-1]=='Enter' for a,_ in calls))

    def test_invalid_answer_rejected_before_touch(self):
        for text in [' ', '\x1b[31m', 'a\nb', '中'*1500]:
            with self.assertRaises(b.Refused): self.submit(2,text)
        with self.assertRaises(b.Refused): self.submit(True)
        with self.assertRaises(b.Refused): self.submit(3)

    def test_defer_returns_to_main_without_submitting(self):
        state={'index':2}; calls=[]
        closed='• Queued follow-up inputs\n  ? 2 questions\n    shift + ← to answer\n› Ask Codex to do anything'+'\n'*20
        def run(*args,**kwargs):
            if args[1]=='capture-pane': return screen(page=f"{state['index']} of 2") if state['index'] else closed
            if args[1]=='send-keys':
                calls.append(args[-1]); state['index']-=1
            return ''
        with patch.object(b,'target'),patch.object(b,'locate',side_effect=lambda _:('binding',io.BytesIO(),{})),patch.object(b,'run',side_effect=run),patch.object(b.time,'sleep'):
            result=b.leave_question({'screenToken':b.screen_token(screen(page='2 of 2'))},{'id':'%1'},'binding')
        self.assertTrue(result['ok'])
        self.assertEqual(calls,['M-Down','M-Down'])

    def test_transport_loss_never_retries(self):
        result,calls=self.submit(1,fail='send-keys')
        self.assertTrue(result['uncertain'])
        self.assertEqual(sum(a[1]=='send-keys' for a,_ in calls),1)
